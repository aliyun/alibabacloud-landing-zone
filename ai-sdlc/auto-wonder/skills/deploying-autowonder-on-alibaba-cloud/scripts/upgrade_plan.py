#!/usr/bin/env python3
"""Shared, fail-closed release baseline and upgrade analysis for both control hosts."""
import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

TRUSTED_REPOSITORIES = {
    'https://github.com/aliyun/alibabacloud-landing-zone',
}
MIGRATION = re.compile(r'^V(0*[1-9][0-9]*)__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$')
SEMVER = r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?'


class PlanError(Exception):
    pass


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def protect_temp_acl(source, target):
    if os.name != 'nt':
        return
    import ctypes
    from ctypes import wintypes
    api = ctypes.WinDLL('advapi32', use_last_error=True)
    get_security = api.GetFileSecurityW
    get_security.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    get_security.restype = wintypes.BOOL
    set_security = api.SetFileSecurityW
    set_security.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p]
    set_security.restype = wintypes.BOOL
    needed = wintypes.DWORD()
    get_security(str(source), 4, None, 0, ctypes.byref(needed))
    if not needed.value:
        raise OSError('Cannot read protected file ACL')
    descriptor = ctypes.create_string_buffer(needed.value)
    if not get_security(str(source), 4, descriptor, needed.value, ctypes.byref(needed)):
        raise OSError('Cannot read protected file ACL')
    # DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION.
    if not set_security(str(target), 4 | 0x80000000, descriptor):
        raise OSError('Cannot protect temporary file ACL')


sys.path.insert(0, str(Path(__file__).resolve().parent))
from operations_hooks import checkpoint as operations_checkpoint, assert_current as operations_assert_current


def atomic_write(path, contents):
    path = Path(path)
    fd, name = tempfile.mkstemp(prefix=path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            protect_temp_acl(path, name)
            stream.write(contents)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def save(path, data):
    operations_assert_current(path)
    atomic_write(path, canonical(data))
    operations_checkpoint(path, data)


def resource_identity(data):
    resources = data.get('resources', {})
    oss = resources.get('oss') or {}
    return dict(rds_instance_id=resources.get('rds_instance_id'),
                rds={'instance_id': (resources.get('rds') or {}).get('instance_id')},
                package_bucket=resources.get('package_bucket'), packageBucket=resources.get('packageBucket'),
                oss={key: oss.get(key) for key in ('control_endpoint', 'public_endpoint', 'runtime_endpoint', 'vpc_endpoint')})


def fingerprint(data):
    upgrade = data['upgrade']
    keys = ('fromCommit toCommit targetRef remote forceRedeploy commits changedFiles environment '
            'pendingMigrations blockedReasons confirmationRequired environmentContractChecked '
            'environmentPlanSha256 targetRecommendedRuntimeVersion').split()
    material = {key: upgrade.get(key) for key in keys}
    material['databaseDestructive'] = upgrade.get('databaseCompatibility', {}).get('destructive')
    material['targetVerificationFingerprint'] = upgrade.get('targetVerification', {}).get('fingerprint')
    for key in ('resourceSetFingerprint', 'sourceBaseline', 'sourceMode', 'environmentSha256'):
        if upgrade.get(key):
            material[key] = upgrade[key]
    if 'resourceIdentity' in upgrade:
        material['resourceIdentity'] = upgrade['resourceIdentity']
        material['actualResourceIdentity'] = resource_identity(data)
    return digest(canonical(material))


def target_fingerprint(data):
    resources = data.get('resources', {})
    instances = resources.get('ecs_instance_ids') or resources.get('ecsInstanceIds') or {}
    tags = resources.get('expected_tags')
    if tags is None:
        tags = data.get('tags', {})
    instance_values = instances.values() if isinstance(instances, dict) else instances
    return digest(canonical(dict(region=data['region'], deploymentId=data['deploymentId'],
        vpcId=resources.get('vpc_id', ''), tags=tags,
        manifestInstanceIds=sorted(set(instance_values)),
        nodes=sorted(data['upgrade']['targetVerification']['nodes'], key=lambda n: n['instanceId']),
        **({'tagVerificationMode': data['upgradeInfo']['tagVerificationMode']} if data.get('upgradeInfo', {}).get('tagVerificationMode') else {}))))


def git(root, *arguments):
    result = subprocess.run(['git', '-C', str(root), *arguments], capture_output=True)
    if result.returncode:
        # Avoid printing remote URLs, credentials, or file contents from stderr.
        raise PlanError('Git command failed: ' + arguments[0])
    return result.stdout


def normalize_url(value):
    value = re.sub(r'^git@([^:]+):', r'https://\1/', value.strip()).rstrip('/')
    value = re.sub(r'^ssh://git@([^/]+)/', r'https://\1/', value)
    return value[:-4] if value.endswith('.git') else value


def check_repository(actual, expected):
    normalized = normalize_url(actual)
    if expected:
        if normalized != normalize_url(expected):
            raise PlanError('Git remote does not match the deployment manifest repository')
    elif normalized not in TRUSTED_REPOSITORIES:
        raise PlanError('Missing repositoryUrl: origin must be a trusted AutoWonder repository')


def contract_path(path):
    return path == 'docs/community/application.env.example' or any(fnmatch.fnmatchcase(path, pattern) for pattern in (
        'src/main/resources/application*.yml',
        'skills/deploying-autowonder-on-alibaba-cloud/assets/templates/*',
        'skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/*',
        'skills/deploying-autowonder-on-alibaba-cloud/scripts/*.sh'))


def relevant(path):
    return contract_path(path) or (path.startswith('docs/migration/') and path.endswith('.sql'))


def git_files(root, commit):
    paths = git(root, 'ls-tree', '-r', '--name-only', commit, '--', '.').decode().splitlines()
    prefix = git(root, 'rev-parse', '--show-prefix').decode().strip()
    result = {}
    for full in paths:
        path = full[len(prefix):] if prefix and full.startswith(prefix) else full
        if relevant(path):
            result[path] = git(root, 'show', commit + ':./' + path)
    return result


def workspace_files(root):
    return {path.relative_to(root).as_posix(): path.read_bytes()
            for folder in ('src/main/resources', 'docs/migration', 'docs/community',
                           'skills/deploying-autowonder-on-alibaba-cloud')
            for path in (root / folder).rglob('*') if path.is_file() and relevant(path.relative_to(root).as_posix())}


def environment(files):
    contract = {}
    for path, contents in files.items():
        if not contract_path(path):
            continue
        text = contents.decode('utf-8')
        tokens = re.findall(r'\$\{([A-Z][A-Z0-9_]*(?::[^}]*)?)\}', text)
        if path == 'docs/community/application.env.example':
            tokens += re.findall(r'^([A-Z][A-Z0-9_]*=.*)$', text, re.M)
        for token in tokens:
            key = re.split('[:=]', token, maxsplit=1)[0]
            entry = {'tokenSha256': digest(token.encode()), 'source': path, 'shellOptionalDefault': token.startswith(key + ':-')}
            if entry not in contract.setdefault(key, []):
                contract[key].append(entry)
    return {key: sorted(entries, key=lambda x: (x['tokenSha256'], x['source'])) for key, entries in sorted(contract.items())}


def migrations(files):
    return {path: digest(content) for path, content in files.items()
            if path.startswith('docs/migration/') and path.endswith('.sql')}


def artifact_evidence(data, manifest_path, active, directory=None, require_live=True):
    saved = data.get('deployment', {}).get('activeReleaseBaseline', {})
    if require_live and saved.get('releaseId') == active:
        data = {**data, 'source': saved.get('source', {}), 'artifacts': saved.get('artifacts', {})}
    source = data.get('source', {})
    baseline = source.get('baseline') or {}
    artifacts = baseline.get('artifacts') or data.get('artifacts') or {}
    base = directory or artifacts.get('releaseDirectory')
    if not base:
        raise PlanError('workspace baseline evidence is unavailable: sealed JAR and migration archive are required')
    base = Path(base)
    if not base.is_absolute():
        base = manifest_path.parent / base
    verified = {}
    for key, default in (('jar', 'auto-wonder.jar'), ('migrations', 'autowonder-migrations.tar.gz')):
        item = artifacts.get(key, {})
        name = item.get('name', default)
        if Path(name).name != name:
            raise PlanError('baseline artifact name is invalid')
        path = base / name
        if not path.is_file():
            raise PlanError('baseline artifact is unavailable: ' + default)
        contents = path.read_bytes()
        if not re.fullmatch('[0-9a-f]{64}', item.get('sha256', '')) or digest(contents) != item['sha256']:
            raise PlanError('baseline artifact checksum mismatch: ' + default)
        verified[key] = path
    if baseline:
        if baseline.get('schemaVersion') != 1:
            raise PlanError('unsupported workspace baseline schema version')
        if baseline.get('releaseId') != active:
            raise PlanError('workspace baseline does not match the active release identity')
        material = {key: value for key, value in baseline.items() if key != 'sha256'}
        if digest(canonical(material)) != baseline.get('sha256'):
            raise PlanError('workspace baseline checksum mismatch')
    elif artifacts['jar']['sha256'][:40] != active:
        raise PlanError('sealed JAR does not establish the active workspace release identity')
    if require_live:
        nodes = data.get('upgradeInventory', {}).get('nodes', [])
        if not nodes or any(node.get('jarSha256') != artifacts['jar']['sha256'] or
                            node.get('migrationsSha256') != artifacts['migrations']['sha256'] for node in nodes):
            raise PlanError('live artifact inventory does not match the sealed baseline; repeat active release inventory')
    files = {}
    with zipfile.ZipFile(verified['jar']) as jar:
        for name in jar.namelist():
            if re.fullmatch(r'BOOT-INF/classes/application[^/]*\.yml', name):
                files['src/main/resources/' + name.split('/')[-1]] = jar.read(name)
    if baseline and baseline.get('applicationSha256') != {path: digest(contents) for path, contents in files.items()}:
        raise PlanError('sealed application configuration does not match the source baseline')
    if 'src/main/resources/application.yml' not in files:
        raise PlanError('sealed JAR is missing the baseline application.yml')
    with tarfile.open(verified['migrations'], 'r:gz') as archive:
        for member in archive.getmembers():
            parts = PurePosixPath(member.name).parts
            if member.isdir():
                continue
            if not member.isfile() or '..' in parts or PurePosixPath(member.name).is_absolute():
                raise PlanError('baseline migration archive contains unsafe entries')
            name = parts[-1]
            if name.endswith('.sql'):
                if len(parts) > 2 or (len(parts) == 2 and parts[0] != 'migration'):
                    raise PlanError('baseline migration archive contains unexpected SQL paths')
                path = 'docs/migration/' + name
                if path in files:
                    raise PlanError('baseline migration archive contains duplicate SQL files')
                files[path] = archive.extractfile(member).read()
    old_environment = baseline.get('environment') if baseline else environment(files)
    old_migrations = migrations(files)
    if baseline and baseline.get('migrations') != old_migrations:
        raise PlanError('baseline migration manifest does not match the sealed archive')
    evidence = dict(kind='sealed-artifacts', releaseId=active,
                    jarSha256=artifacts['jar']['sha256'], migrationsSha256=artifacts['migrations']['sha256'])
    evidence['sha256'] = digest(canonical(dict(evidence=evidence, environment=old_environment, migrations=old_migrations)))
    return old_environment, old_migrations, evidence


def seal(data, manifest_path, root):
    active = data['repositoryCommit']
    if data.get('source', {}).get('releaseId') != active:
        raise PlanError('source release identity does not match the sealed release')
    files = workspace_files(root)
    baseline = dict(schemaVersion=1, releaseId=active, releaseVersion=data.get('releaseVersion'), artifacts=data['artifacts'],
                    environment=environment(files), migrations=migrations(files),
                    applicationSha256={path: digest(content) for path, content in files.items() if fnmatch.fnmatchcase(path, 'src/main/resources/application*.yml')})
    baseline['sha256'] = digest(canonical(baseline))
    data.setdefault('source', {})['baseline'] = baseline
    # Also re-read the sealed artifacts before persisting the source contract.
    artifact_evidence(data, manifest_path, active, require_live=False)
    save(manifest_path, data)


def recommended_runtime(files):
    text = files.get('src/main/resources/application.yml', b'').decode('utf-8')
    parents = {}
    for line in text.splitlines():
        match = re.match(r'^( *)([A-Za-z0-9-]+)\s*:\s*(.*?)\s*$', line)
        if not match:
            continue
        indent, key, value = len(match[1]), match[2], match[3]
        parents = {level: name for level, name in parents.items() if level < indent}
        path = [parents[level] for level in sorted(parents)] + [key]
        if path == ['autowonder', 'runtime', 'recommended-version']:
            value = value.strip('"\'')
            value = re.sub(r'^\$\{AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:([^}]+)\}$', r'\1', value)
            if re.fullmatch(SEMVER, value):
                return value
            break
        if not value:
            parents[indent] = key
    raise PlanError('Target recommended runtime version must have a semantic-version default')


def candidate_env(path, runtime):
    lines = path.read_text(encoding='utf-8-sig').splitlines()
    if any(re.search(r'[`;]|\$\(', line) for line in lines):
        raise PlanError('candidate environment file contains executable shell syntax')
    values = {}
    for line in lines:
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r'([A-Z][A-Z0-9_]*)=(.*)', line)
        if not match:
            raise PlanError('candidate environment file has invalid syntax')
        if match[1] in values:
            raise PlanError('candidate environment file contains duplicate keys')
        values[match[1]] = match[2].strip().strip('"\'')
    updated = [line for line in lines if not line.startswith('AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=')]
    updated.append('AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=' + runtime)
    contents = ('\n'.join(updated) + '\n').encode()
    atomic_write(path, contents)
    return values, digest(contents)


def analyze(old_environment, old_migrations, target_files, values):
    new_environment = environment(target_files)
    old_keys, new_keys = set(old_environment), set(new_environment)
    added = sorted(new_keys - old_keys)
    # A key's meaning follows its tokens, not the paths declaring it.
    tokens = lambda entries: sorted({entry['tokenSha256'] for entry in entries})
    changed = sorted(key for key in old_keys & new_keys if tokens(old_environment[key]) != tokens(new_environment[key]))
    managed = {'AUTOWONDER_SECRET_MASTER_KEY', 'AUTOWONDER_JWT_SECRET', 'AUTOWONDER_PUBLIC_BASE_URL',
               'AUTOWONDER_RUNTIME_RECOMMENDED_VERSION', 'AUTOWONDER_VERSION', 'S3_ENABLED', 'S3_PUBLIC_ENDPOINT', 'S3_REGION'}
    required = []
    for key in added:
        if key in managed:
            continue
        if key in {'S3_ENDPOINT', 'S3_ACCESS_KEY_ID', 'S3_ACCESS_KEY_SECRET'} and values.get('S3_ENABLED', '').lower() != 'true':
            continue
        if any(not (entry['source'].startswith('skills/') and '/scripts/' in entry['source'] and
                       entry['source'].endswith('.sh') and entry['shellOptionalDefault'])
               for entry in new_environment[key]):
            required.append(key)
    blocked = ['required environment value missing: ' + key for key in required if not values.get(key)]
    new_migrations = migrations(target_files)
    versions = {}
    for path in new_migrations:
        match = MIGRATION.fullmatch(path.split('/')[-1])
        if not match:
            blocked.append('invalid migration filename: ' + path)
            continue
        version = int(match[1])
        if version in versions:
            blocked.append('duplicate migration version: ' + str(version))
        versions[version] = path
    maximum = max([int(m[1]) for path in old_migrations if (m := MIGRATION.fullmatch(path.split('/')[-1]))] or [0])
    for path, checksum in old_migrations.items():
        if new_migrations.get(path) != checksum:
            blocked.append('published migration changed: ' + path)
    pending = []
    for path in sorted(set(new_migrations) - set(old_migrations)):
        match = MIGRATION.fullmatch(path.split('/')[-1])
        if not match:
            continue
        version = int(match[1])
        if version <= maximum:
            blocked.append('new migration version is not greater than published versions: ' + str(version))
        risks = sorted(set(re.findall(r'\b(ALTER|DROP|TRUNCATE|RENAME|CREATE|UPDATE|DELETE|INSERT)\b', target_files[path].decode('utf-8').upper())))
        pending.append(dict(version=version, file=path, sha256=new_migrations[path], riskOperations=risks))
    destructive = any(set(item['riskOperations']) & {'DROP', 'TRUNCATE', 'RENAME'} for item in pending)
    return dict(environment=dict(added=added, removed=sorted(old_keys-new_keys), changed=changed, required=required),
                pendingMigrations=sorted(pending, key=lambda x: x['version']), blockedReasons=sorted(set(blocked)),
                confirmationRequired=bool(pending), databaseCompatibility=dict(
                    status='review-required' if pending else 'not-required', rollingAllowed=not pending, destructive=destructive))


def resource_fingerprint(data):
    recorded = data.get('upgradeInfo', {}).get('resourceSetFingerprint')
    if recorded:
        return recorded
    resources = data.get('resources', {})
    instances = resources.get('ecs_instance_ids') or resources.get('ecsInstanceIds') or {}
    return digest(canonical(dict(deploymentId=data['deploymentId'], region=data['region'],
        vpcId=resources.get('vpc_id', ''), ecsInstanceIds=sorted(set(instances.values() if isinstance(instances, dict) else instances)))))


def resolve_source(root):
    marker = 'skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/autowonder.service'
    if (root / marker).is_file():
        return root
    candidates = []
    for current, directories, files in os.walk(root):
        directories[:] = [name for name in directories if name not in {'.git', 'target', 'node_modules'}]
        if 'VERSION' in files and 'pom.xml' in files and (Path(current) / marker).is_file():
            candidates.append(Path(current))
    if len(candidates) > 1:
        raise PlanError('multiple AutoWonder project directories found in source worktree')
    return candidates[0] if candidates else root


def content_identity(root):
    material = []
    paths = []
    for current, directories, names in os.walk(root):
        directories[:] = [name for name in directories if name not in {'.git', 'target', 'node_modules', 'upgrade-info', '__pycache__'}
                           and not (Path(current) == root and name in {'.operations-cache', 'deployments', 'logs'})
                           and not (Path(current) == root / 'skills' and name != 'deploying-autowonder-on-alibaba-cloud')
                           and not (Path(current).name == 'frontend' and name in {'dist', 'coverage'})]
        paths.extend(Path(current) / name for name in names if name not in {'.git', '.DS_Store'}
                     and not (Path(current).relative_to(root).parts[:1] == ('frontend',) and name.endswith('.tsbuildinfo'))
                     and not (Path(current) / name).is_symlink())
    for path in sorted(paths):
        relative = path.relative_to(root).as_posix()
        material.append(relative + '\t' + digest(path.read_bytes()) + '\n')
    return digest(''.join(material).encode())[:40]


def plan(args, data):
    previous = data.get('upgrade', {})
    migration = previous.get('databaseMigration', {})
    database_changed = previous.get('databaseMutationStarted') or migration.get('status') in {'running', 'failed', 'applied', 'passed'} or migration.get('applied')
    if database_changed and data.get('deployment', {}).get('activeCommit') != previous.get('toCommit'):
        raise PlanError('unfinished database mutation: resume the existing plan or review recovery before replanning')
    root = resolve_source(Path(args.source_dir).resolve())
    active = args.current_commit or data.get('deployment', {}).get('activeCommit') or data.get('repositoryCommit')
    if not re.fullmatch('[0-9a-f]{40}', active or ''):
        raise PlanError('active release identity is unavailable')
    inventory = data.get('upgradeInventory', {})
    verification = data.get('upgrade', {}).get('targetVerification', {})
    node_ids = lambda nodes: sorted(set(node.get('instanceId') for node in nodes))
    if inventory.get('targetVerificationFingerprint') != verification.get('fingerprint') or node_ids(inventory.get('nodes', [])) != node_ids(verification.get('nodes', [])) or not 0 <= time.time() - inventory.get('verifiedEpoch', 0) <= 1800:
        raise PlanError('active release inventory is stale or does not cover the verified target set')
    recorded_resource = data.get('upgradeInfo', {}).get('resourceSetFingerprint')
    if recorded_resource and inventory.get('resourceSetFingerprint') != recorded_resource:
        raise PlanError('active release inventory resource set is stale')
    if inventory.get('status') != 'verified' or inventory.get('activeCommit') != active:
        raise PlanError('verified active release inventory does not match the active release identity')
    workspace_mode = args.workspace_current_content
    if workspace_mode:
        if not args.force_redeploy:
            raise PlanError('current-workspace planning is only permitted for an explicit forced redeployment')
        saved_version = data.get('deployment', {}).get('activeReleaseBaseline', {}).get('releaseVersion') or data.get('releaseVersion') or data.get('source', {}).get('baseline', {}).get('releaseVersion')
        version_file = root / 'VERSION'
        if not saved_version or not version_file.is_file() or version_file.read_text().strip() != saved_version:
            raise PlanError('same-version redeployment requires the recorded active release version')
        old_environment, old_migrations, baseline = artifact_evidence(data, Path(args.manifest), active, args.baseline_dir)
        target_files = workspace_files(root)
        if migrations(target_files) != old_migrations:
            raise PlanError('same-version redeployment cannot change migration files; use the normal upgrade planner')
        target = content_identity(root)
        changed_files, commits = [], []
    else:
        if git(root, 'status', '--porcelain', '--untracked-files=no').strip():
            raise PlanError('source has tracked changes')
        branch = git(root, 'branch', '--show-current').decode().strip()
        if branch not in ('', 'master'):
            raise PlanError('source must be local master or a detached remote-master worktree before planning')
        check_repository(git(root, 'remote', 'get-url', args.remote).decode().strip(), data.get('repositoryUrl'))
        git(root, 'fetch', args.remote, 'master')
        if branch:
            try:
                git(root, 'merge', '--ff-only', 'refs/remotes/' + args.remote + '/master')
            except PlanError:
                raise PlanError('local master diverges from remote master; preserve it and plan from a clean detached remote-master worktree')
        target = git(root, 'rev-parse', 'refs/remotes/' + args.remote + '/master^{commit}').decode().strip()
        if git(root, 'rev-parse', 'HEAD^{commit}').decode().strip() != target:
            raise PlanError('planning worktree does not match the fetched remote master commit')
        if active == target and not args.force_redeploy:
            print(json.dumps(dict(status='already-latest', activeCommit=active, targetCommit=target, targetRef='master')))
            return
        target_files = git_files(root, target)
        try:
            old_commit = git(root, 'rev-parse', '--verify', active + '^{commit}').decode().strip()
        except PlanError:
            old_commit = None
        if old_commit:
            old_files = git_files(root, old_commit)
            old_environment, old_migrations = environment(old_files), migrations(old_files)
            baseline = dict(kind='git', releaseId=active, commit=old_commit)
            changed_files = []
            for line in git(root, 'diff', '--relative', '--name-status', active, target, '--', '.').decode().splitlines():
                status, path = line.split('\t', 1)
                changed_files.append(dict(status=status, path=path))
            commits = [dict(zip(('commit', 'subject'), line.split('\t', 1))) for line in
                       git(root, 'log', '--reverse', '--format=%H%x09%s', active + '..' + target, '--', '.').decode().splitlines()]
        else:
            old_environment, old_migrations, baseline = artifact_evidence(data, Path(args.manifest), active, args.baseline_dir)
            changed_files, commits = [], []
    env_path = Path(args.env_file or data.get('localContext', {}).get('protectedEnvFile', ''))
    if not env_path.is_file():
        raise PlanError('candidate protected environment file is required before an upgrade plan can be approved')
    if os.name != 'nt' and env_path.stat().st_mode & 0o077:
        raise PlanError('candidate protected environment file must have mode 600')
    runtime = recommended_runtime(target_files)
    values, env_hash = candidate_env(env_path, runtime)
    findings = analyze(old_environment, old_migrations, target_files, values)
    verification = data['upgrade']['targetVerification']
    if baseline['kind'] == 'sealed-artifacts':
        saved = data.setdefault('deployment', {}).get('activeReleaseBaseline', {})
        if saved.get('releaseId') != active:
            data['deployment']['activeReleaseBaseline'] = dict(releaseId=active, releaseVersion=data.get('releaseVersion'), source=data.get('source', {}), artifacts=data.get('artifacts', {}))
    data.update(mode='upgrade', repositoryRef='master', repositoryCommit=target, recommendedRuntimeVersion=runtime,
                acceptance={}, phase='upgrade-plan', status='blocked' if findings['blockedReasons'] else 'planned')
    data['upgrade'] = dict(fromCommit=active, toCommit=target, sourceBaseline=baseline, targetRef='master', remote=args.remote,
        forceRedeploy=args.force_redeploy, commits=commits, changedFiles=changed_files, **findings,
        environmentContractChecked=True, environmentPlanSha256=env_hash, environmentSha256=env_hash,
        environmentValidated=False, targetRecommendedRuntimeVersion=runtime, resourceSetFingerprint=resource_fingerprint(data),
        resourceIdentity=resource_identity(data),
        databaseBackup={'status': 'pending'}, migrationApproved=False, approval={'status': 'pending'},
        planStatus=data['status'], targetVerification=verification)
    if workspace_mode:
        data['repositoryRef'] = 'workspace-current-content'
        data['upgrade'].update(sourceMode='workspace-current-content', targetRef='workspace-current-content', remote='none')
    data['upgrade']['planFingerprint'] = fingerprint(data)
    if args.env_file:
        data.setdefault('localContext', {})['candidateEnvFile'] = str(Path(args.env_file).resolve())
    save(args.manifest, data)
    if findings['blockedReasons']:
        raise PlanError('upgrade plan is blocked; inspect sanitized manifest findings')
    print(json.dumps(dict(phase=data['phase'], status=data['status'], mode=data['mode'], upgrade=data['upgrade'])))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['plan', 'seal', 'fingerprint', 'target-fingerprint', 'content-identity', 'resolve-source'])
    parser.add_argument('--manifest')
    parser.add_argument('--source-dir')
    parser.add_argument('--env-file')
    parser.add_argument('--remote', default='origin')
    parser.add_argument('--current-commit')
    parser.add_argument('--baseline-dir')
    parser.add_argument('--force-redeploy', action='store_true')
    parser.add_argument('--workspace-current-content', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'resolve-source':
            print(resolve_source(Path(args.source_dir).resolve()))
            return 0
        if args.command == 'content-identity':
            print(content_identity(resolve_source(Path(args.source_dir).resolve())))
            return 0
        if not args.manifest:
            parser.error('--manifest is required')
        if args.command in ('plan', 'seal'):
            operations_assert_current(Path(args.manifest))
        data = json.loads(Path(args.manifest).read_text(encoding='utf-8-sig'))
        if args.command == 'fingerprint':
            print(fingerprint(data))
        elif args.command == 'target-fingerprint':
            print(target_fingerprint(data))
        elif args.command == 'seal':
            seal(data, Path(args.manifest), resolve_source(Path(args.source_dir).resolve()))
        else:
            plan(args, data)
    except (PlanError, OSError, ValueError, KeyError, zipfile.BadZipFile, tarfile.TarError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
