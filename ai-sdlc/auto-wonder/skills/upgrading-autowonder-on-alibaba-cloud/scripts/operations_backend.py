"""Explicit default-workspace local-state migration, invoked after a checkpoint.

The caller must persist the original recovery bundle and migration intent before
calling this function, and checkpoint its resulting manifest afterwards. An
uncertain migration is never retried automatically. This module never manages
bucket lifecycle; the dedicated operations bucket must already exist.
"""
import json
import os
from pathlib import Path
import re
import subprocess

from operations_bundle import BundleError, private_write, read_file


class BackendError(RuntimeError):
    pass


def _read_json(path):
    try:
        value = json.loads(read_file(path))
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (OSError, ValueError, BundleError):
        raise BackendError('Required migration input is missing or invalid') from None


def _state_identity(state):
    if (state.get('version') != 4 or not isinstance(state.get('lineage'), str)
            or not state['lineage'] or not isinstance(state.get('serial'), int)
            or isinstance(state['serial'], bool) or state['serial'] < 0
            or not isinstance(state.get('resources'), list)):
        raise BackendError('Terraform state lineage, serial, or resources are invalid')
    identities = []
    for resource in state['resources']:
        if (not isinstance(resource, dict) or not all(isinstance(resource.get(k), str)
                and resource[k] for k in ('mode', 'type', 'name', 'provider'))
                or not isinstance(resource.get('instances'), list)):
            raise BackendError('Terraform resource identity is incomplete')
        for instance in resource['instances']:
            if not isinstance(instance, dict) or not isinstance(instance.get('attributes'), dict):
                raise BackendError('Terraform resource instance identity is incomplete')
            identity = [resource.get('module'), resource['mode'], resource['type'], resource['name'],
                        resource['provider'], instance.get('index_key'), instance.get('deposed'),
                        instance['attributes'].get('id', instance['attributes'])]
            identities.append(json.dumps(identity, sort_keys=True, separators=(',', ':')))
    return sorted(identities)


def _environment():
    config = Path(os.environ.get('ALIBABA_CLOUD_CLI_CONFIG_FILE', str(Path.home() / '.aliyun' / 'config.json')))
    try:
        profile = next(p for p in _read_json(config)['profiles'] if p.get('name') == 'auto-wonder')
        key, secret, token = profile['access_key_id'], profile['access_key_secret'], profile.get('sts_token', '')
        if not key or not secret or any(not isinstance(x, str) or '\n' in x or '\r' in x for x in (key, secret, token)):
            raise ValueError()
    except (KeyError, StopIteration, ValueError, TypeError):
        raise BackendError('The auto-wonder profile needs usable AK/STS credentials') from None
    env = {k: v for k, v in os.environ.items() if not k.startswith(
        ('TF_LOG', 'TF_CLI_ARGS', 'ALICLOUD_', 'ALIBABA_CLOUD_', 'OSS_'))}
    env.update(ALICLOUD_ACCESS_KEY=key, ALICLOUD_SECRET_KEY=secret,
               ALICLOUD_SECURITY_TOKEN=token, TF_IN_AUTOMATION='1', TF_INPUT='0')
    return env


def _terraform(root, arguments, environment):
    try:
        result = subprocess.run(['terraform', '-chdir=' + str(root)] + arguments,
            env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=600, check=False)
    except (OSError, subprocess.SubprocessError):
        raise BackendError('Terraform migration outcome is unknown; reconcile before retrying') from None
    if result.returncode:
        raise BackendError('Terraform migration failed or is unknown; reconcile before retrying')
    return result.stdout


def migrate_local_state(manifest_path, store):
    manifest_path = Path(manifest_path).absolute()
    manifest = _read_json(manifest_path)
    metadata = manifest.get('terraform', {})
    mode = manifest.get('stateMode', metadata.get('stateMode'))
    if mode in ('remote', 'oss'):
        return
    if mode != 'local':
        raise BackendError('Terraform state mode must be explicit before migration')
    binding = manifest.get('operationsStore', {})
    bucket, region, deployment = binding.get('bucket'), binding.get('region'), manifest.get('deploymentId')
    if (not isinstance(bucket, str) or not re.fullmatch(r'aw-ops-[a-z0-9-]+', bucket)
            or not isinstance(region, str) or not re.fullmatch(r'[a-z0-9-]+', region)
            or not isinstance(deployment, str) or not re.fullmatch(r'[a-zA-Z0-9_-]+', deployment)
            or binding.get('deploymentId') != deployment or region != manifest.get('region')):
        raise BackendError('A matching dedicated operations bucket binding is required')
    root_value = manifest.get('localContext', {}).get('terraformDirectory') or metadata.get('workingDirectory')
    if not root_value:
        raise BackendError('The actual Terraform working directory is required')
    root = Path(root_value).expanduser()
    if not root.is_absolute():
        root = manifest_path.parent / root
    if not root.is_dir():
        raise BackendError('The Terraform working directory is missing')
    marker = root / '.operations-state-migration.json'
    if marker.exists():
        raise BackendError('A previous migration needs reconciliation; automatic retry is forbidden')
    workspace_dir = root / 'terraform.tfstate.d'
    selected = root / '.terraform' / 'environment'
    if (os.environ.get('TF_WORKSPACE', 'default') != 'default' or os.environ.get('TF_DATA_DIR')
            or (workspace_dir.exists() and any(workspace_dir.iterdir()))
            or (selected.exists() and read_file(selected).decode().strip() != 'default')):
        raise BackendError('Only the default Terraform workspace can be migrated automatically')
    sources = list(root.glob('*.tf')) + list(root.glob('*.tf.json'))
    if not sources:
        raise BackendError('Terraform configuration is missing')
    for source in sources:
        text = read_file(source).decode('utf-8')
        if re.search(r'\bbackend\s*"', text) or (source.name.endswith('.json') and re.search(r'"backend"\s*:', text)):
            raise BackendError('An existing backend definition requires manual reconciliation')
    cache = root / '.terraform' / 'terraform.tfstate'
    if cache.exists() and _read_json(cache).get('backend', {}).get('type') not in (None, '', 'local'):
        raise BackendError('The initialized backend is not local; reconcile manifest state mode')
    backend = root / 'backend.hcl'
    definition = root / 'backend.tf'
    backup = root / 'terraform.tfstate.pre-operations-migration.backup'
    if any(path.exists() for path in (backend, definition, backup)):
        raise BackendError('Existing migration files require reconciliation')
    original_path = root / 'terraform.tfstate'
    original_bytes = read_file(original_path)
    original = _read_json(original_path)
    identities = _state_identity(original)
    if str(store.identity()) != str(binding.get('accountUid')):
        raise BackendError('The auto-wonder account does not match the operations bucket')
    environment = _environment()
    key = 'deploy/terraform-state/' + deployment + '/terraform.tfstate'
    if store.get(bucket, key) is not None:
        raise BackendError('Destination Terraform state already exists; reconcile before migration')
    # Keep an immutable local backup and a persistent intent marker even when
    # Terraform succeeds but pulling/validating/saving the manifest fails.
    private_write(backup, original_bytes)
    private_write(marker, json.dumps({'status': 'unknown', 'bucket': bucket, 'key': key,
        'lineage': original['lineage'], 'serial': original['serial']}).encode())
    configuration = {'bucket': bucket, 'key': key, 'region': region,
                     'endpoint': 'oss-' + region + '.aliyuncs.com', 'acl': 'private'}
    private_write(backend, (''.join(k + ' = ' + json.dumps(v) + '\n' for k, v in configuration.items()) + 'encrypt = true\n').encode())
    private_write(definition, b'terraform {\n  backend "oss" {}\n}\n')
    _terraform(root, ['init', '-migrate-state', '-force-copy', '-input=false',
                     '-backend-config=' + str(backend)], environment)
    output = _terraform(root, ['state', 'pull'], environment)
    try:
        migrated = json.loads(output)
        if not isinstance(migrated, dict):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise BackendError('Migrated Terraform state is unreadable; reconcile before retrying') from None
    if (_state_identity(migrated) != identities or migrated['lineage'] != original['lineage']
            or migrated['serial'] < original['serial']):
        raise BackendError('Migrated state does not preserve the original lineage, serial, and resources')
    manifest['stateMode'] = 'remote'
    metadata = manifest.setdefault('terraform', {})
    metadata.update(stateReference=str(backend), backendDirectory=str(root), backendStatus='ready',
                    stateBucket=bucket, stateKey=key)
    if 'stateMode' in metadata:
        metadata['stateMode'] = 'remote'
    try:
        private_write(manifest_path, (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode())
    except (OSError, BundleError):
        raise BackendError('Cloud state migrated but local manifest save failed; reconcile before retrying') from None
    # This marker intentionally remains. A stale local manifest must not initiate
    # another migration after a successful cloud copy.
