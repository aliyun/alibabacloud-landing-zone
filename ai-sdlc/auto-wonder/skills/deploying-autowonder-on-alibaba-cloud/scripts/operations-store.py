#!/usr/bin/env python3
"""Cloud-first deployment state. This command is invoked by both Skill adapters."""
from __future__ import annotations

import argparse
import copy
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import uuid

from operation_metrics import measure

SCRIPTS = Path(__file__).resolve().parent
UPGRADE = SCRIPTS
REGIONS = ('cn-zhangjiakou', 'cn-hangzhou', 'cn-shanghai', 'cn-beijing')
UPGRADE_FIELDS = {'upgrade', 'upgradeInventory', 'rollingUpgrade'}


class StateError(RuntimeError):
    """Sanitized state validation failure."""


class LocalFolderRequired(StateError):
    pass


class SelectionRequired(StateError):
    def __init__(self, deployments):
        super().__init__('Multiple cloud deployments found; specify --region and --deployment-id')
        self.deployments = [{k: entry[k] for k in ('region', 'deploymentId')} for entry in deployments]


def canonical(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def parse(value):
    try:
        result = json.loads(value)
    except (ValueError, UnicodeError):
        raise StateError('Invalid operations state JSON') from None
    if not isinstance(result, dict):
        raise StateError('Operations state must be an object')
    return result


def read(path):
    return parse(Path(path).read_bytes())


def private_write(path, value):
    from operations_bundle import private_write as write_private
    write_private(Path(path), value)


@contextmanager
def internal_writes():
    old = os.environ.get('AUTOWONDER_OPERATIONS_INTERNAL')
    os.environ['AUTOWONDER_OPERATIONS_INTERNAL'] = '1'
    try:
        yield
    finally:
        if old is None:
            os.environ.pop('AUTOWONDER_OPERATIONS_INTERNAL', None)
        else:
            os.environ['AUTOWONDER_OPERATIONS_INTERNAL'] = old


def upgrade_info():
    spec = importlib.util.spec_from_file_location('operations_upgrade_info', UPGRADE / 'upgrade_info.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OperationsState:
    def __init__(self, store):
        self.store = store

    def teardown(self, manifest_path):
        """Finish recovery storage only after the main and state backend are gone.

        A failed attempt retains its lock and all local recovery inputs. The same
        manifest can resume an interrupted object purge; never release its lock
        and let a checkpoint publish references to already deleted blobs.
        """
        data = read(manifest_path)
        terraform = data.get('terraform', {})
        if (terraform.get('mainDestroyVerified') is not True or
                terraform.get('backendStatus') != 'destroyed' or terraform.get('pendingOperation') or
                data.get('phase') != 'terraform-destroy' or data.get('status') != 'destroyed'):
            raise StateError('Verified main and backend destruction is required before operations teardown')
        binding = data.get('operationsStore')
        if not binding:
            return
        self.validate_identity(binding)
        if any(binding.get(key) != data.get(key) for key in ('accountUid', 'region', 'deploymentId')):
            raise StateError('Operations teardown manifest identity mismatch')
        state_hash = digest((data['accountUid'] + '|' + data['region'] + '|' + data['deploymentId']).encode())[:12]
        state_bucket = 'aw-tfstate-' + data['deploymentId'] + '-' + state_hash
        if terraform.get('stateBucket') != state_bucket:
            raise StateError('State bucket teardown binding mismatch')
        if self.store._api('get-bucket-info', state_bucket, missing=True) is not None:
            raise StateError('State backend bucket destruction is not verified')
        self.store.validate_teardown_bucket(binding)
        attempt = data.get('operationsTeardown')
        coordinates = {key: binding[key] for key in ('bucket', 'accountUid', 'region', 'deploymentId')}
        if attempt is None:
            self.assert_current(manifest_path)
            attempt = {**coordinates, 'status': 'pending', 'owner': uuid.uuid4().hex}
            data['operationsTeardown'] = attempt
            private_write(manifest_path, canonical(data))
        elif (attempt.get('status') != 'pending' or
              any(attempt.get(key) != value for key, value in coordinates.items()) or
              not re.fullmatch('[0-9a-f]{32}', attempt.get('owner', ''))):
            raise StateError('Operations teardown attempt does not match this deployment')
        lock = canonical({'owner': attempt['owner'], 'operation': 'teardown'})
        existing = self.store.get(binding['bucket'], 'write-lock.json')
        if existing is None:
            self.store.put(binding['bucket'], 'write-lock.json', lock, create_only=True)
        elif existing != lock:
            raise StateError('Another operation owns the deployment write lock')
        # Also verify retries: a failed lock acquisition must not authorize a newer revision.
        self.assert_current(manifest_path)
        self.store.destroy_operations_bucket(binding, lock)
        attempt['status'] = 'destroyed'
        attempt.pop('owner', None)
        data.pop('operationsStore')
        private_write(manifest_path, canonical(data))

    def validate_identity(self, identity):
        if identity.get('accountUid') != self.store.identity() or identity.get('region') != self.store.region:
            raise StateError('Operations store account or region mismatch')
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', identity.get('deploymentId', '')):
            raise StateError('Invalid deployment identity')
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', identity.get('bucket', '')):
            raise StateError('Invalid operations bucket')

    def current(self, identity):
        self.validate_identity(identity)
        raw = self.store.get(identity['bucket'], 'current.json')
        if raw is None:
            return None
        value = parse(raw)
        if value.get('schemaVersion') != 1 or any(value.get(k) != identity[k] for k in ('accountUid', 'region', 'deploymentId')):
            raise StateError('Operations store identity or schema mismatch')
        if not re.fullmatch('[0-9a-f]{64}', value.get('revision', '')):
            raise StateError('Invalid operations revision')
        if digest(canonical({key: item for key, item in value.items() if key != 'revision'})) != value['revision']:
            raise StateError('Operations current pointer checksum mismatch')
        return value

    def _blob(self, bucket, prefix, content):
        sha = digest(content)
        key = prefix + sha
        existing = self.store.get(bucket, key)
        if existing is None:
            self.store.put(bucket, key, content, create_only=True)
            existing = self.store.get(bucket, key)
        if existing is None or digest(existing) != sha:
            raise StateError('Operations object checksum mismatch')
        return {'key': key, 'sha256': sha}

    def _get_blob(self, bucket, descriptor, scope):
        if not isinstance(descriptor, dict):
            raise StateError('Operations object reference is missing')
        key, sha = descriptor.get('key', ''), descriptor.get('sha256', '')
        if not re.fullmatch('[0-9a-f]{64}', sha) or key not in (scope + '/objects/' + sha, scope + '/records/' + sha):
            raise StateError('Invalid operations object reference')
        value = self.store.get(bucket, key)
        if value is None or digest(value) != sha:
            raise StateError('Operations object is missing or corrupt; local fallback is forbidden')
        return value

    def commit(self, identity, bundle, expected_revision):
        self.validate_identity(identity)
        manifest = copy.deepcopy(bundle['manifest'])
        if any(manifest.get(k) != identity[k] for k in ('deploymentId', 'region', 'accountUid')):
            raise StateError('Manifest does not match operations identity')
        manifest.pop('operationsStore', None)
        bucket = identity['bucket']
        lock = canonical({'owner': uuid.uuid4().hex, 'expectedRevision': expected_revision})
        acquired = False
        try:
            self.store.put(bucket, 'write-lock.json', lock, create_only=True)
            acquired = True
            previous = self.current(identity)
            if (previous or {}).get('revision') != expected_revision:
                raise StateError('A newer cloud revision exists; resolve again before changing resources')
            committed = set()
            if previous:
                for scope in ('deploy', 'upgrade'):
                    record = parse(self._get_blob(bucket, previous.get('records', {}).get(scope), scope))
                    if not isinstance(record.get('files'), dict):
                        raise StateError('Incomplete operations record')
                    for descriptor in record['files'].values():
                        if not isinstance(descriptor, dict):
                            raise StateError('Invalid committed object reference')
                        sha = descriptor.get('sha256', '')
                        if not re.fullmatch('[0-9a-f]{64}', sha) or descriptor.get('key') != scope + '/objects/' + sha:
                            raise StateError('Invalid committed object reference')
                        committed.add(descriptor['key'])
            checked = {}
            records = {scope: {'manifest': {}, 'files': {}} for scope in ('deploy', 'upgrade')}
            for key, value in manifest.items():
                records['upgrade' if key in UPGRADE_FIELDS else 'deploy']['manifest'][key] = value
            for path, content in bundle['files'].items():
                parts = PurePosixPath(path).parts
                if not parts or parts[0] not in records or '..' in parts or '\\' in path or PurePosixPath(path).is_absolute():
                    raise StateError('Unsafe operations bundle path')
                scope = parts[0]
                sha = digest(content)
                key = scope + '/objects/' + sha
                if key not in checked:
                    if key in committed:
                        # These immutable bytes were verified before publication.
                        # Check availability without downloading a release per write;
                        # restore always downloads and verifies the full SHA-256.
                        if self.store.head(bucket, key) != len(content):
                            raise StateError('Committed operations object is missing or has changed size')
                        checked[key] = {'key': key, 'sha256': sha}
                    else:
                        checked[key] = self._blob(bucket, scope + '/objects/', content)
                records[scope]['files'][path] = checked[key]
            references = {scope: self._blob(bucket, scope + '/records/', canonical(record)) for scope, record in records.items()}
            env_contents = [value for key, value in bundle['files'].items() if key.endswith('.env')]
            current = {'schemaVersion': 1, **{k: identity[k] for k in ('accountUid', 'region', 'deploymentId')},
                       'records': references, 'complete': bundle['manifest'].get('operationsBundle', {}).get('complete', True),
                       'localStateRequiresMigration': bundle['manifest'].get('operationsBundle', {}).get('localStateRequiresMigration', False),
                       'terraformSecretsRecorded': any(key.endswith('/terraform-secrets.env') for key in bundle['files']),
                       'runtimeSecretsRecorded': any(b'AUTOWONDER_SECRET_MASTER_KEY=' in value and b'AUTOWONDER_JWT_SECRET=' in value for value in env_contents)}
            for field in ('terraformSecretsRecorded', 'runtimeSecretsRecorded'):
                if (previous or {}).get(field) and not current[field]:
                    raise StateError('Previously recorded secrets are missing; restore before checkpointing')
            current['revision'] = digest(canonical(current))
            # A historical entry is immutable; current is the only mutable commit point.
            self._blob(bucket, 'deploy/records/', canonical(current))
            self.store.put(bucket, 'current.json', canonical(current))
            if self.store.get(bucket, 'current.json') != canonical(current):
                raise StateError('Operations commit could not be verified; stop and resolve cloud state')
            return current
        finally:
            if acquired:
                # Never remove a lock owned by another writer, even after corruption.
                if self.store.get(bucket, 'write-lock.json') == lock:
                    self.store.delete(bucket, 'write-lock.json')

    def download(self, identity):
        current = self.current(identity)
        if current is None:
            return None, None
        bundle = {'manifest': {}, 'files': {}}
        bucket = identity['bucket']
        for scope in ('deploy', 'upgrade'):
            record = parse(self._get_blob(bucket, current.get('records', {}).get(scope), scope))
            if not isinstance(record.get('manifest'), dict) or not isinstance(record.get('files'), dict):
                raise StateError('Incomplete operations record')
            if bundle['manifest'].keys() & record['manifest'].keys():
                raise StateError('Conflicting operations records')
            bundle['manifest'].update(record['manifest'])
            for path, descriptor in record['files'].items():
                if not path.startswith(scope + '/'):
                    raise StateError('Operations file is outside its scope')
                bundle['files'][path] = self._get_blob(bucket, descriptor, scope)
        if any(bundle['manifest'].get(k) != identity[k] for k in ('accountUid', 'region', 'deploymentId')):
            raise StateError('Downloaded manifest identity mismatch')
        return bundle, current

    def assert_current(self, manifest):
        binding = read(manifest).get('operationsStore')
        if not binding:
            return
        current = self.current(binding)
        if current is None or current['revision'] != binding.get('revision'):
            raise StateError('Local operations cache is stale or cloud state is missing; resolve first')

    @measure('checkpoint')
    def save(self, manifest_path, root, allow_incomplete=False, initialize=False):
        from operations_bundle import collect
        manifest_path, root = Path(manifest_path).resolve(), Path(root).resolve()
        data = read(manifest_path)
        account = self.store.identity()
        if data.get('accountUid') not in (None, '', account):
            raise StateError('Deployment account differs from authenticated account')
        data['accountUid'] = account
        binding = data.get('operationsStore')
        if binding:
            self.assert_current(manifest_path)
            identity = binding
        else:
            if not initialize:
                return None
            identity = {'accountUid': account, 'region': data['region'], 'deploymentId': data['deploymentId'],
                        'bucket': self.store.ensure_bucket(data['deploymentId'])}
            if self.current(identity) is not None:
                raise StateError('Cloud state already exists; use resolve instead of importing local state')
        # Collect at the original location so historical relative artifact paths
        # retain their meaning. Authentication enrichment is only written on commit.
        from operations_bundle import BundleError
        complete = True
        with measure('bundle_collect') as counts:
            try:
                bundle = collect(manifest_path, root)
            except BundleError:
                if not allow_incomplete:
                    raise
                bundle = collect(manifest_path, root, allow_incomplete=True)
                complete = False
            counts.update(files=len(bundle['files']), bytes=sum(map(len, bundle['files'].values())))
        if not (data.get('deployment', {}).get('activeCommit') or data.get('repositoryCommit')) or not data.get('artifacts', {}).get('jar'):
            complete = False
        bundle['manifest']['operationsBundle']['complete'] = complete
        bundle['manifest']['accountUid'] = account
        current = self.commit(identity, bundle, (binding or {}).get('revision'))
        data['operationsStore'] = {k: identity[k] for k in ('bucket', 'accountUid', 'region', 'deploymentId')}
        data['operationsStore'].update(revision=current['revision'], projectRoot=str(root), ready=complete,
                                       bootstrap=not complete and (initialize and allow_incomplete or (binding or {}).get('bootstrap', False)),
                                       localStateRequiresMigration=current['localStateRequiresMigration'],
                                       terraformSecretsRecorded=current['terraformSecretsRecorded'],
                                       runtimeSecretsRecorded=current['runtimeSecretsRecorded'])
        private_write(manifest_path, canonical(data))
        return data['operationsStore']

    @measure('restore')
    def restore(self, identity, root):
        from operations_bundle import restore
        bundle, current = self.download(identity)
        if bundle is None:
            return None
        root = Path(root).resolve()
        cache = root / '.operations-cache' / identity['deploymentId']
        cache.mkdir(parents=True, exist_ok=True, mode=0o700)
        destination = Path(tempfile.mkdtemp(prefix='restore-', dir=cache))
        try:
            path = restore(bundle, destination)
            data = read(path)
            data['operationsStore'] = {k: identity[k] for k in ('bucket', 'accountUid', 'region', 'deploymentId')}
            complete = current.get('complete', True)
            data['operationsStore'].update(revision=current['revision'], projectRoot=str(root), ready=complete,
                                           bootstrap=not complete,
                                           localStateRequiresMigration=current.get('localStateRequiresMigration', False),
                                           terraformSecretsRecorded=current.get('terraformSecretsRecorded', False),
                                           runtimeSecretsRecorded=current.get('runtimeSecretsRecorded', False))
            data.setdefault('localContext', {})['sourceDirectory'] = str(root)
            # Validate an upgrade recovery before replacing its local working manifest.
            if complete:
                private_write(path, canonical(data))
                info = upgrade_info()
                info.context_from_manifest(root, path)
            working = (root / 'upgrade-info' / identity['deploymentId'] / 'manifest.json') if complete else (
                root / 'deployments' / identity['deploymentId'] / 'deployment-manifest.json')
            private_write(working, canonical(data))
            if complete:
                rebuild_discovery(root, working)
            return {'status': 'resolved' if complete else 'deployment-resume-required', 'manifest': str(working), 'source': 'oss',
                    'refreshRequired': True, 'cloudProfile': 'auto-wonder'}
        except Exception:
            import shutil
            shutil.rmtree(destination)
            raise

    def migrate_backend(self, manifest_path, root):
        """The old state and unknown intent are durable before Terraform is invoked."""
        from operations_backend import migrate_local_state
        manifest_path = Path(manifest_path)
        data = read(manifest_path)
        if data.get('operationsMigration', {}).get('status') == 'unknown':
            raise StateError('Previous Terraform state migration is unresolved; reconcile before retrying')
        if data.get('stateMode', data.get('terraform', {}).get('stateMode')) not in ('local',):
            return
        data['operationsMigration'] = {'status': 'unknown', 'operation': 'local-to-operations-oss'}
        private_write(manifest_path, canonical(data))
        binding = self.save(manifest_path, root)
        lock = canonical({'owner': uuid.uuid4().hex, 'operation': 'terraform-state-migration'})
        acquired = False
        try:
            self.store.put(binding['bucket'], 'write-lock.json', lock, create_only=True)
            acquired = True
            self.assert_current(manifest_path)
            migrate_local_state(manifest_path, self.store)
        finally:
            if acquired and self.store.get(binding['bucket'], 'write-lock.json') == lock:
                self.store.delete(binding['bucket'], 'write-lock.json')
        data = read(manifest_path)
        data['operationsMigration']['status'] = 'verified'
        private_write(manifest_path, canonical(data))
        self.save(manifest_path, root)


def rebuild_discovery(root, manifest):
    """Rebuild disposable discovery files without re-registering/resetting upgrade state."""
    root, manifest = Path(root).resolve(), Path(manifest).resolve()
    info = upgrade_info()
    data = read(manifest)
    context = info.context_from_manifest(root, manifest)
    with internal_writes():
        info.atomic_write_json(manifest.parent / 'discovery.json', info.build_discovery(root, context))
        inventory = info.build_inventory(None, context, context['resources'])
        info.atomic_write_json(manifest.parent / 'inventory.json', inventory)
        index_path = root / 'upgrade-info' / 'index.json'
        index = read(index_path) if index_path.exists() else {'schemaVersion': 1, 'deployments': {}}
        index['activeDeploymentId'] = data['deploymentId']
        index.setdefault('deployments', {})[data['deploymentId']] = {
            'deploymentDirectory': context['deploymentDirectory'],
            'infoDirectory': 'upgrade-info/' + data['deploymentId']}
        info.atomic_write_json(index_path, index)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('resolve', 'initialize', 'checkpoint', 'assert-current', 'teardown'))
    parser.add_argument('--manifest')
    parser.add_argument('--project-root')
    parser.add_argument('--deployment-dir')
    parser.add_argument('--deployment-id')
    parser.add_argument('--region')
    parser.add_argument('--allow-incomplete', action='store_true')
    args = parser.parse_args(argv)
    try:
        from operations_oss import OssStore
        if args.command == 'teardown':
            data = read(args.manifest)
            state = OperationsState(OssStore(data['region'], data.get('accountUid')))
            state.teardown(args.manifest)
            print(json.dumps({'status': 'operations-destroyed', 'deploymentId': data['deploymentId']}))
            return 0
        if args.command in ('checkpoint', 'assert-current'):
            data = read(args.manifest)
            binding = data.get('operationsStore')
            if not binding:
                return 0
            state = OperationsState(OssStore(binding['region'], binding['accountUid']))
            if args.command == 'assert-current':
                state.assert_current(args.manifest)
            else:
                state.save(args.manifest, binding['projectRoot'], allow_incomplete=not binding.get('ready', True))
            return 0
        if args.command == 'initialize':
            data = read(args.manifest)
            state = OperationsState(OssStore(data['region'], data.get('accountUid')))
            state.save(args.manifest, args.project_root, args.allow_incomplete, initialize=True)
            print(json.dumps({'status': 'initialized', 'deploymentId': data['deploymentId']}))
            return 0
        result = resolve(args, OssStore)
        print(json.dumps(result))
        return 0
    except LocalFolderRequired:
        print(json.dumps({'status': 'deployment-folder-required', 'message': 'Cloud record is absent; provide the historical project-root deployment folder'}))
        return 5
    except SelectionRequired as exc:
        print(json.dumps({'status': 'deployment-selection-required', 'deployments': exc.deployments}))
        return 3
    except Exception as exc:
        # Even CLI errors can contain credentials. Only our fixed state errors are safe.
        from operations_oss import StoreError
        from operations_bundle import BundleError
        from operations_backend import BackendError
        message = str(exc) if isinstance(exc, (StateError, StoreError, BundleError, BackendError)) else 'Operations state failed; no cloud mutation is authorized'
        print(json.dumps({'status': 'blocked', 'error': message}), file=sys.stderr)
        return 1


def resolve(args, store_factory):
    root = Path(args.project_root or Path.cwd()).resolve()
    hint = read(args.manifest) if args.manifest and Path(args.manifest).is_file() else {}
    deployment_id = args.deployment_id or hint.get('deploymentId')
    region = args.region or hint.get('region')
    candidates = []
    stores = {}
    for selected_region in (region,) if region else REGIONS:
        store = store_factory(selected_region)
        expected_account = hint.get('accountUid') or hint.get('operationsStore', {}).get('accountUid')
        if expected_account and expected_account != store.identity():
            raise StateError('Explicit deployment belongs to a different authenticated account')
        stores[selected_region] = store
        candidates.extend((store, item) for item in store.discover()
                          if not deployment_id or item['deploymentId'] == deployment_id)
    if len(candidates) > 1:
        raise SelectionRequired([entry for _, entry in candidates])
    if candidates:
        store, identity = candidates[0]
        if hint.get('operationsStore') and hint['operationsStore'].get('bucket') != identity['bucket']:
            raise StateError('Discovered bucket differs from the explicit deployment binding')
        result = OperationsState(store).restore(identity, root)
        if result:
            restored = read(result['manifest'])
            if restored.get('operationsMigration', {}).get('status') == 'unknown':
                raise StateError('Previous Terraform state migration is unresolved; reconcile before retrying')
            if restored.get('operationsStore', {}).get('localStateRequiresMigration'):
                state = OperationsState(store)
                state.migrate_backend(result['manifest'], root)
                result = state.restore(read(result['manifest'])['operationsStore'], root)
            return result
    if hint.get('operationsStore'):
        raise StateError('Previously initialized cloud state is missing; do not overwrite it from local cache')
    info = upgrade_info()
    # Existing working state takes precedence over an older deployment handoff.
    cached = info.cached_registration(root)
    if cached and (not deployment_id or read(cached['manifest']).get('deploymentId') == deployment_id):
        located = cached
    else:
        with internal_writes():
            try:
                located = info.locate(argparse.Namespace(project_root=str(root), manifest=args.manifest,
                                                        deployment_dir=args.deployment_dir))
            except info.DeploymentFolderRequired:
                raise LocalFolderRequired() from None
    manifest = Path(located['manifest'])
    data = read(manifest)
    if data.get('operationsStore'):
        raise StateError('Cloud state is missing for a registered deployment; recovery requires review')
    if deployment_id and data['deploymentId'] != deployment_id:
        raise StateError('Local deployment differs from the requested cloud deployment')
    if region and data['region'] != region:
        raise StateError('Local deployment region differs from the requested region')
    # Registration normalizes away deployment inputs. Preserve those facts, but keep
    # the complete newer working state authoritative for releases and checkpoints.
    original = root / 'deployments' / data['deploymentId'] / 'deployment-manifest.json'
    if original.is_file() and original.resolve() != manifest.resolve():
        historical = read(original)
        if any(historical.get(k) != data.get(k) for k in ('deploymentId', 'region')):
            raise StateError('Historical deployment identity conflicts with upgrade state')
        data = merge(historical, data)
        private_write(manifest, canonical(data))
    verify_legacy(manifest)
    store = stores.get(data['region']) or store_factory(data['region'])
    state = OperationsState(store)
    binding = state.save(manifest, root, initialize=True)
    if binding.get('localStateRequiresMigration'):
        state.migrate_backend(manifest, root)
        binding = read(manifest)['operationsStore']
    result = state.restore(binding, root)
    result['source'] = 'local-imported-to-oss'
    return result


def merge(original, current):
    result = copy.deepcopy(original)
    for key, value in current.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def verify_legacy(manifest):
    """Refresh cloud ownership/release evidence before publishing a historical baseline."""
    commands = []
    if os.name == 'nt':
        commands = [['powershell', '-NoProfile', '-File', str(UPGRADE / 'verify-deployment-targets.ps1'), '-Manifest', str(manifest)],
                    ['powershell', '-NoProfile', '-File', str(UPGRADE / 'legacy-inventory.ps1'), 'upgrade-inventory', '-Manifest', str(manifest)]]
    else:
        commands = [['bash', str(UPGRADE / 'verify-deployment-targets.sh'), '--manifest', str(manifest)],
                    ['bash', str(UPGRADE / 'legacy-inventory.sh'), 'upgrade-inventory', '--manifest', str(manifest)]]
    for command in commands:
        result = subprocess.run(command, capture_output=True)
        if result.returncode:
            raise StateError('Historical deployment ownership or active release verification failed')


if __name__ == '__main__':
    raise SystemExit(main())
