"""Pure local, integrity-checked portable deployment recovery bundles.

Local state is copied verbatim. Its presence never implies backend migration;
operators must run Terraform's explicit backend migration before discarding it.
"""
import copy
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import subprocess
import tempfile


class BundleError(ValueError):
    pass


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def safe_relative(value):
    if not isinstance(value, str) or not value or '\\' in value:
        raise BundleError('Invalid recovery path')
    path = PurePosixPath(value)
    if path.is_absolute() or PureWindowsPath(value).drive or '..' in path.parts or str(path) != value:
        raise BundleError('Invalid recovery path')
    return path


def is_link(path):
    # macOS exposes its temporary directories through these system aliases.
    return path.is_symlink() and str(path) not in ('/var', '/tmp', '/etc')


def read_file(path):
    if any(is_link(item) for item in (path, *path.parents)):
        raise BundleError('Recovery inputs must not be symbolic links')
    if not path.is_file():
        raise BundleError('Required recovery file is missing')
    return path.read_bytes()


def baseline_walk(value, verify=False):
    if isinstance(value, dict):
        for item in value.values():
            baseline_walk(item, verify)
        if value.get('schemaVersion') == 1 and 'artifacts' in value and 'sha256' in value:
            actual = sha256(canonical({key: item for key, item in value.items() if key != 'sha256'}))
            if verify and actual != value['sha256']:
                raise BundleError('Sealed baseline checksum mismatch')
            if not verify:
                value['sha256'] = actual
    elif isinstance(value, list):
        for item in value:
            baseline_walk(item, verify)


def protect_directory(path):
    path = Path(path)
    if os.name == 'nt':
        # Replace the DACL, including pre-existing explicit grants. chmod and
        # icacls /inheritance:r alone cannot isolate an existing Windows folder.
        quoted_path = str(path).replace("'", "''")
        script = (
            "$ErrorActionPreference='Stop'; "
            "$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; "
            "$acl=New-Object System.Security.AccessControl.DirectorySecurity; "
            "$acl.SetAccessRuleProtection($true,$false); $acl.SetOwner($sid); "
            "$rule=New-Object System.Security.AccessControl.FileSystemAccessRule "
            "($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'); "
            "$acl.AddAccessRule($rule); Set-Acl -LiteralPath '" + quoted_path + "' -AclObject $acl"
        )
        encoded = base64.b64encode(script.encode('utf-16-le')).decode('ascii')
        result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive',
                                 '-EncodedCommand', encoded], capture_output=True)
        if result.returncode:
            raise BundleError('Cannot protect the recovery directory ACL')
    else:
        os.chmod(path, 0o700)


def private_write(path, contents):
    """Atomically save bytes under a protected parent without exposing contents."""
    path = Path(path).absolute()
    if any(is_link(item) for item in (path, *path.parents)):
        raise BundleError('Private file destination must not be a symbolic link')
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    protect_directory(path.parent)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            if os.name != 'nt':
                os.chmod(temporary, 0o600)
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


CREDENTIAL_KEYS = {'access_key', 'secret_key', 'security_token', 'access_key_id', 'access_key_secret'}


def reject_inline_credentials(content, json_source=False):
    """Require CLI/environment authentication instead of uploading inline credentials."""
    if json_source:
        source = json.loads(content)
        def inspect(value):
            if isinstance(value, dict):
                if CREDENTIAL_KEYS.intersection(value):
                    raise BundleError('Inline Terraform authentication credentials cannot be archived')
                for child in value.values():
                    inspect(child)
            elif isinstance(value, list):
                for child in value:
                    inspect(child)
        terraform = source.get('terraform', {})
        inspect(terraform)
        inspect(source.get('provider', {}))
        return
    # Skip strings and comments while locating a block's matching closing brace.
    token = re.compile(r'"(?:\\.|[^"\\])*"|//[^\n]*|\#[^\n]*|/\*[\s\S]*?\*/|[{}]')
    assignment = re.compile(r'\b(?:' + '|'.join(sorted(CREDENTIAL_KEYS)) + r')\s*=')
    for start in re.finditer(r'\b(?:backend|provider)\s+"[^"\n]+"\s*\{', content):
        depth = 1
        end = len(content)
        for item in token.finditer(content, start.end()):
            if item.group() == '{':
                depth += 1
            elif item.group() == '}':
                depth -= 1
                if depth == 0:
                    end = item.start()
                    break
        if assignment.search(content[start.end():end]):
            raise BundleError('Inline Terraform authentication credentials cannot be archived')


def collect(manifest_path, project_root, allow_incomplete=False):
    manifest_path, project_root = Path(manifest_path).absolute(), Path(project_root).absolute()
    data = json.loads(read_file(manifest_path))
    baseline_walk(data, True)
    data.pop('operationsBundle', None)
    data.pop('operationsStore', None)
    files, fields = {}, []
    mappings = {}

    def resolve(value, artifact=False):
        value = value.replace('\\', '/')
        if PureWindowsPath(value).drive and os.name != 'nt':
            raise BundleError('A foreign absolute path must be resolved on its original host before import')
        path = Path(value).expanduser()
        if path.is_absolute():
            return path
        candidates = [manifest_path.parent / path, project_root / path] if artifact else [project_root / path, manifest_path.parent / path]
        return next((candidate for candidate in candidates if candidate.exists()), candidates[0])

    def add(path, target, optional=False, contents=None):
        safe_relative(target)
        if optional and not path.exists() and not path.is_symlink():
            return False
        content = read_file(path) if contents is None else contents
        if target in files and files[target] != content:
            raise BundleError('Conflicting recovery file paths')
        files[target] = content
        mappings[str(path.absolute())] = target
        return True

    context = data.setdefault('localContext', {})
    tf_value = context.get('terraformDirectory') or data.get('terraform', {}).get('workingDirectory')
    if not tf_value:
        candidates = [manifest_path.parent / name for name in ('terraform', 'infrastructure')]
        tf_path = next((x for x in candidates if x.is_dir()), None)
        if tf_path is None:
            raise BundleError('Actual Terraform working directory is required')
    else:
        tf_path = resolve(tf_value)
    if not tf_path.is_dir():
        raise BundleError('Actual Terraform working directory is missing')
    state_mode = data.get('stateMode') or data.get('terraform', {}).get('stateMode')
    if not state_mode:
        reference = data.get('terraform', {}).get('stateReference') or ''
        state_mode = 'local' if reference.endswith('.tfstate') else ('remote' if reference.endswith('backend.hcl') else None)
    if state_mode not in ('local', 'remote', 'oss') and not (state_mode is None and allow_incomplete):
        raise BundleError('Terraform backend mode is required for recovery')
    retain_local_state = state_mode == 'local'
    roots = set()

    def module_closure(directory):
        directory = Path(os.path.abspath(directory))
        if directory in roots:
            return
        if any(is_link(x) for x in (directory, *directory.parents)):
            raise BundleError('Terraform modules must not be symbolic links')
        roots.add(directory)
        sources = list(directory.glob('*.tf')) + list(directory.glob('*.tf.json'))
        if not sources and not (allow_incomplete and directory == Path(os.path.abspath(tf_path))):
            raise BundleError('Terraform source is missing')
        for source in sources:
            content = read_file(source).decode('utf-8')
            reject_inline_credentials(content, source.name.endswith('.json'))
            if source.name.endswith('.json'):
                parsed = json.loads(content)
                references = [value.get('source', '') for value in parsed.get('module', {}).values()]
            else:
                references = re.findall(r'\bsource\s*=\s*"([^"\n]+)"', content)
            for reference in references:
                if reference.startswith(('./', '../')):
                    module_closure(directory / reference)
                elif Path(reference).is_absolute():
                    module_closure(Path(reference))
                elif PureWindowsPath(reference).drive:
                    raise BundleError('Foreign Terraform module source cannot be resolved')
    module_closure(tf_path)
    common = Path(os.path.commonpath([str(path) for path in roots]))
    # Keep the root name even when there are no sibling modules.
    if common in roots:
        common = common.parent
    local_state = False
    for root in sorted(roots):
        prefix = 'deploy/terraform/' + root.relative_to(common).as_posix()
        mappings[str(root)] = prefix
        for path in sorted(root.iterdir()):
            name = path.name
            if path.is_dir():
                continue
            if name.endswith(('.tfstate', '.tfstate.backup')) and not retain_local_state:
                if state_mode is None:
                    raise BundleError('Terraform backend mode is required before excluding local state')
                continue
            if name.endswith(('.tf', '.tf.json', '.tfvars', '.tfvars.json', '.tfstate', '.tfstate.backup')) or name in ('.terraform.lock.hcl', 'terraform-secrets.env', 'terraform.tfstate', 'terraform.tfstate.backup'):
                add(path, prefix + '/' + name)
                local_state |= name.endswith('.tfstate')
        workspace_states = root / 'terraform.tfstate.d'
        if state_mode is None and workspace_states.exists():
            raise BundleError('Terraform backend mode is required before excluding workspace state')
        if retain_local_state and workspace_states.exists():
            for path in sorted(workspace_states.rglob('*')):
                if path.is_symlink():
                    raise BundleError('Local state must not contain symbolic links')
                if path.is_file() and path.name.endswith(('.tfstate', '.tfstate.backup')):
                    add(path, prefix + '/' + path.relative_to(root).as_posix())
                    local_state |= path.name.endswith('.tfstate')
        if root == Path(os.path.abspath(tf_path)) and not allow_incomplete:
            for name in ('terraform-secrets.env', 'deployment.auto.tfvars.json'):
                if prefix + '/' + name not in files:
                    raise BundleError('Required Terraform inputs or original secrets are missing')
    # Rewrite absolute local module sources to relative references in saved sources.
    for root in sorted(roots):
        for source in list(root.glob('*.tf')) + list(root.glob('*.tf.json')):
            target = mappings[str(root)] + '/' + source.name
            text = files[target].decode('utf-8')
            for module in roots:
                relative = os.path.relpath(module, root).replace(os.sep, '/')
                if not relative.startswith('.'):
                    relative = './' + relative
                text = text.replace(json.dumps(str(module)), json.dumps(relative))
            files[target] = text.encode('utf-8')
    # Include literal file/templatefile inputs outside *.tf without copying caches.
    for root in sorted(roots):
        prefix = mappings[str(root)]
        for source in list(root.glob('*.tf')) + list(root.glob('*.tf.json')):
            text = read_file(source).decode('utf-8')
            for reference in re.findall(r'(?:templatefile|file|filebase64)\s*\(\s*"([^"]+)"', text):
                reference = reference.replace('${path.module}/', '')
                if '${' in reference or Path(reference).is_absolute() or '..' in PurePosixPath(reference).parts:
                    raise BundleError('Terraform file input cannot be relocated safely')
                add(root / reference, prefix + '/' + reference)
    context['terraformDirectory'] = str(tf_path)
    if state_mode == 'local' and not local_state and not allow_incomplete:
        raise BundleError('Local Terraform state is missing')

    artifacts_seen = {}
    discard = {'operationsBundle', 'operationsStore', 'planPath', 'logFile', 'logPath', 'stdoutFile', 'stderrFile', 'evidenceDirectory', 'cacheDirectory', 'signedUrl', 'presignedUrl', 'profilePath', 'profileFile', 'handoffFile', 'planJsonPath'}
    directory_keys = {'sourceDirectory', 'projectRoot', 'deploymentDirectory', 'infoDirectory', 'outputDirectory'}
    file_keys = {'protectedEnvFile', 'candidateEnvFile', 'activeEnvFile', 'previousActiveEnvFile', 'environmentFile', 'envFile', 'tfvarsFile', 'secretsFile', 'backendConfigFile', 'stateFile', 'localStateFile', 'stateReference', 'systemdFile', 'systemdUnitFile'}

    def walk(value, trail=()):
        if isinstance(value, list):
            for index, child in enumerate(value):
                walk(child, trail + (index,))
            return
        if not isinstance(value, dict):
            return
        # Earlier Windows releases nested filename-keyed build evidence and used
        # `directory`; normalize that producer shape before collecting or mapping.
        if trail == ('upgrade', 'release') and 'artifacts' in value:
            if 'directory' in value:
                value['releaseDirectory'] = value.pop('directory')
            if 'releaseDirectory' in value:
                for key, name in (('jar', 'auto-wonder.jar'), ('schema', 'autowonder-schema.sql'),
                                  ('templates', 'autowonder-community-templates.sql'),
                                  ('migrations', 'autowonder-migrations.tar.gz'), ('systemdUnit', 'autowonder.service')):
                    if name in value['artifacts']:
                        value[key] = dict(value['artifacts'][name], name=name)
        if 'releaseDirectory' in value:
            if any(not isinstance(value.get(key), dict) for key in ('jar', 'migrations')):
                raise BundleError('Sealed release is missing JAR or migration metadata')
            directory = resolve(value['releaseDirectory'], True)
            identity = str(directory.absolute())
            target = artifacts_seen.setdefault(identity, 'upgrade/releases/' + str(len(artifacts_seen)))
            mappings[identity] = target
            for key, entry in value.items():
                if not isinstance(entry, dict) or 'name' not in entry:
                    continue
                name = entry['name']
                if str(safe_relative(name)) != PurePosixPath(name).name:
                    raise BundleError('Invalid artifact filename')
                path = directory / name
                if allow_incomplete and not path.exists():
                    # A declared sealed artifact is never an optional future output.
                    raise BundleError('Declared sealed artifact is missing')
                contents = read_file(path)
                if not re.fullmatch('[0-9a-f]{64}', entry.get('sha256', '')) or sha256(contents) != entry['sha256']:
                    raise BundleError('Sealed artifact checksum mismatch')
                add(path, target + '/' + name, contents=contents)
            if not any(name.startswith(target + '/') for name in files):
                raise BundleError('Sealed release contains no artifacts')
        for key in sorted(value):
            child = value[key]
            if key in discard:
                del value[key]
                continue
            if isinstance(child, str) and child:
                mapped = None
                if key in directory_keys:
                    mapped = '.' if key in ('sourceDirectory', 'projectRoot') else ('upgrade' if key == 'infoDirectory' else 'deploy')
                elif key in file_keys or key.lower().endswith(('envfile', 'environmentfile')):
                    if not retain_local_state and (key == 'localStateFile' or child.endswith(('.tfstate', '.tfstate.backup'))):
                        if key == 'stateReference':
                            raise BundleError('Remote backend points to a local state file')
                        del value[key]
                        continue
                    path = resolve(child)
                    mapped = mappings.get(str(path.absolute()))
                    if mapped is None:
                        if path.name == 'backend.hcl':
                            contents = read_file(path).decode('utf-8')
                            # Do not persist transient CLI authentication in backend config.
                            contents = re.sub(r'(?m)^\s*(?:access_key|secret_key|security_token|access_key_id|access_key_secret|token|profile|shared_credentials_file)\s*=.*(?:\n|$)', '', contents)
                            mapped = 'deploy/backend/backend.hcl'
                            add(path, mapped, contents=contents.encode())
                        else:
                            mapped = 'deploy/protected/' + sha256(canonical(list(trail + (key,))))[:12] + '/' + path.name
                            if not add(path, mapped, optional=allow_incomplete):
                                del value[key]
                                continue
                elif key in ('terraformDirectory', 'workingDirectory', 'backendDirectory', 'releaseDirectory'):
                    path = resolve(child, key == 'releaseDirectory')
                    mapped = mappings.get(str(path.absolute()))
                    if key == 'backendDirectory':
                        mapped = 'deploy/backend'
                    if mapped is None:
                        raise BundleError('Unresolved recovery directory')
                if mapped is not None:
                    value[key] = mapped
                    fields.append({'keys': list(trail + (key,)), 'path': mapped})
            walk(value.get(key), trail + (key,))
    walk(data)
    if not context.get('protectedEnvFile') and not allow_incomplete:
        raise BundleError('Original active application environment is required')
    # Baseline digests include releaseDirectory. Validate before changing it,
    # then reseal the same artifact hashes under their portable storage location.
    baseline_walk(data)
    data['operationsBundle'] = dict(schemaVersion=1, pathFields=fields,
        fileSha256={path: sha256(content) for path, content in files.items()},
        localStateRequiresMigration=retain_local_state)
    return {'manifest': data, 'files': files}


def restore(bundle, destination):
    data = copy.deepcopy(bundle['manifest'])
    metadata = data.get('operationsBundle', {})
    files = bundle['files']
    if metadata.get('schemaVersion') != 1 or set(files) != set(metadata.get('fileSha256', {})):
        raise BundleError('Recovery file inventory mismatch')
    baseline_walk(data, True)
    for name, content in files.items():
        safe_relative(name)
        if not isinstance(content, bytes) or sha256(content) != metadata['fileSha256'][name]:
            raise BundleError('Recovery file checksum mismatch')
    destination = Path(destination).absolute()
    if any(is_link(path) for path in (destination, *destination.parents)):
        raise BundleError('Recovery destination must not be a symbolic link')
    directory_fields = {'terraformDirectory', 'workingDirectory', 'backendDirectory',
                        'releaseDirectory', 'sourceDirectory', 'projectRoot',
                        'deploymentDirectory', 'infoDirectory', 'outputDirectory'}
    directories = set()
    for field in metadata.get('pathFields', []):
        relative = field['path']
        if relative != '.':
            safe_relative(relative)
        keys = field.get('keys')
        if not isinstance(keys, list) or not keys or not isinstance(keys[-1], str):
            raise BundleError('Invalid recovery path field')
        value = data
        try:
            for key in keys[:-1]:
                value = value[key]
            if value[keys[-1]] != relative:
                raise BundleError('Recovery path metadata does not match the manifest')
        except (KeyError, IndexError, TypeError):
            raise BundleError('Invalid recovery path field') from None
        if keys[-1] in directory_fields:
            directories.add(relative)
        value[keys[-1]] = str(destination / relative)
    baseline_walk(data)
    # Validate every destination before writing any byte; never follow symlinks.
    for name in list(files) + ['manifest.json']:
        path = destination / name
        if any(is_link(item) for item in (path, *path.parents)):
            raise BundleError('Recovery destination contains a symbolic link')
        if path.exists():
            raise BundleError('Recovery destination already contains a file')
    for name in directories:
        path = destination / name
        if any(is_link(item) for item in (path, *path.parents)):
            raise BundleError('Recovery directory contains a symbolic link')
        if (path.exists() and not path.is_dir()) or name in files or name == 'manifest.json':
            raise BundleError('Recovery directory conflicts with a file')
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    protect_directory(destination)
    for name in sorted(directories):
        path = destination / name
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        protect_directory(path)
    for name, content in {**files, 'manifest.json': canonical(data)}.items():
        path = destination / name
        private_write(path, content)
    return destination / 'manifest.json'
