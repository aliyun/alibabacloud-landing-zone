"""Private, versioned tools. No cloud calls and no global environment changes.

Lock columns: name, os, arch, version, HTTPS URL, SHA256, archive format,
archive-relative executable. Python must always come from the pinned cache.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import urllib.error
import urllib.parse
import zipfile

LOCK_FILE = Path(__file__).with_name('runtime-lock.tsv')
TOOL_COMMANDS = {'python': 'python3', 'jdk': 'java', 'maven': 'mvn', 'terraform': 'terraform',
                 'aliyun': 'aliyun', 'ossutil': 'ossutil', 'jq': 'jq'}


def host_platform():
    system = {'Darwin': 'darwin', 'Linux': 'linux', 'Windows': 'windows'}.get(platform.system())
    arch = {'x86_64': 'x86_64', 'AMD64': 'x86_64', 'arm64': 'arm64', 'aarch64': 'arm64', 'ARM64': 'arm64'}.get(platform.machine())
    if not system or not arch:
        raise RuntimeError('unsupported tool platform')
    return system, arch


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_lock(path):
    rows = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) != 8:
            raise RuntimeError('invalid runtime lock row')
        name, system, arch, version, url, digest, fmt, executable = fields
        key = name, system, arch
        relative = PurePosixPath(executable)
        if (name not in TOOL_COMMANDS or system not in ('darwin', 'linux', 'windows')
                or arch not in ('arm64', 'x86_64') or not re.fullmatch(r'[0-9][0-9A-Za-z.+_-]*', version)
                or not url.startswith('https://') or not re.fullmatch(r'(?:[a-f0-9]{64}|[a-f0-9]{128})', digest)
                or fmt not in ('tar.gz', 'zip', 'raw') or relative.is_absolute() or '..' in relative.parts
                or '\\' in executable or ':' in executable or not relative.parts or key in rows):
            raise RuntimeError('invalid or duplicate runtime lock row')
        rows[key] = fields
    return rows


def run_probe(path, arguments, env=None):
    command = [str(path), *arguments]
    environment = dict(os.environ if env is None else env)
    if os.name == 'nt' and Path(path).suffix.lower() in ('.cmd', '.bat'):
        # The only CMD arguments here are fixed version probes. An environment
        # substitution keeps %, spaces, Chinese and shell metacharacters in the
        # path out of cmd.exe's command construction.
        environment['AUTOWONDER_PROBE_TOOL'] = str(path)
        command = [environment.get('COMSPEC', 'cmd.exe'), '/d', '/s', '/c',
                   '""%AUTOWONDER_PROBE_TOOL%" ' + ' '.join(arguments) + '"']
    return subprocess.run(command, capture_output=True, text=True, timeout=15, env=environment)


def qualified(path, name, pinned_version=None, env=None):
    try:
        argument = {'python': '--version', 'jdk': '-version', 'maven': '--version', 'terraform': 'version',
                    'aliyun': 'version', 'ossutil': 'version', 'jq': '--version'}[name]
        if name == 'python':
            arch = host_platform()[1]
            aliases = ('arm64', 'aarch64') if arch == 'arm64' else ('x86_64', 'amd64')
            code = ('import ssl,hashlib,zipfile,platform,struct,argparse,json,tarfile,urllib.request,subprocess,pathlib,ipaddress,shutil; '
                    f'assert platform.machine().lower() in {aliases!r}; '
                    'assert struct.calcsize("P") == 8; '
                    'print("Python " + platform.python_version())')
            result = run_probe(path, ['-I', '-c', code], env)
        else:
            result = run_probe(path, [argument], env)
        text = result.stdout + result.stderr
        if result.returncode:
            return False
        pattern = {'python': r'Python (\d+\.\d+\.\d+)', 'jdk': r'version "(\d+(?:\.\d+){0,2})',
                   'maven': r'Apache Maven (\d+\.\d+\.\d+)', 'terraform': r'Terraform v(\d+\.\d+\.\d+)',
                   'aliyun': r'(?m)^v?(\d+\.\d+\.\d+)\s*$',
                   'ossutil': r'(?m)^(?:ossutil version:?\s*)?v?(\d+\.\d+\.\d+)\s*$',
                   'jq': r'jq-(\d+\.\d+(?:\.\d+)?)'}[name]
        match = re.search(pattern, text)
        if not match:
            return False
        version = tuple(int(part) for part in match[1].split('.'))
        if name == 'jdk':
            compiler = Path(path).resolve().with_name('javac.exe' if os.name == 'nt' else 'javac')
            compiled = run_probe(compiler, ['-version'], env)
            compiler_version = re.search(r'javac (\d+(?:\.\d+){0,2})', compiled.stdout + compiled.stderr)
            if compiled.returncode or not compiler_version or compiler_version[1] != match[1]:
                return False
        if pinned_version is not None:
            return match[1] == pinned_version
        return {'python': version >= (3, 13), 'jdk': version[0] == 21,
                'maven': (3, 9, 9) <= version < (4,), 'terraform': (1, 5, 0) <= version < (2,),
                'aliyun': (3, 0, 0) <= version < (4,), 'ossutil': (2, 0, 0) <= version < (3,),
                'jq': (1, 8, 2) <= version < (2,)}[name]
    except (OSError, ValueError, subprocess.SubprocessError):
        return False


def download(url, destination):
    # Retry only transport failures, never an HTTP rejection or failed checksum.
    # Report the original host, not exception text (which may contain URL secrets).
    try:
        parsed = urllib.parse.urlsplit(url)
        host = parsed.hostname or 'unknown'
    except ValueError:
        raise RuntimeError('tool download failed (host=unknown; stage=url)') from None
    if not re.fullmatch(r'[A-Za-z0-9.:-]+', host):
        host = 'unknown'
    if parsed.scheme != 'https':
        raise RuntimeError(f'tool download failed (host={host}; stage=https)')
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                if not response.geturl().startswith('https://'):
                    raise RuntimeError(f'tool download failed (host={host}; stage=https-redirect)')
                # Each attempt replaces partial bytes from the previous response.
                with Path(destination).open('wb') as out:
                    shutil.copyfileobj(response, out)
            return
        except urllib.error.HTTPError as error:
            error.close()
            raise RuntimeError(f'tool download failed (host={host}; stage=http)') from None
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 1:
                raise RuntimeError(f'tool download failed (host={host}; stage=network)') from None
        except OSError:
            raise RuntimeError(f'tool download failed (host={host}; stage=local-io)') from None


def safe_directory(path):
    path = Path(path).absolute()
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise RuntimeError('tool cache cannot contain symlink directories')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    return path


def cache_valid(directory, row, env=None):
    binary = directory / row[7]
    try:
        expected = row[5] + '\n' + sha256(binary) + '\n'
        return (directory / '.verified').read_text() == expected and qualified(binary, row[0], row[3], env)
    except (OSError, ValueError):
        return False


def extract(archive, directory, fmt):
    try:
        if fmt == 'tar.gz':
            with tarfile.open(archive, 'r:gz') as source:
                source.extractall(directory, filter='data')
        else:
            with zipfile.ZipFile(archive) as source:
                for entry in source.infolist():
                    path = PurePosixPath(entry.filename)
                    if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or ':' in entry.filename or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                        raise RuntimeError('unsafe tool archive')
                source.extractall(directory)
                if os.name != 'nt':
                    for entry in source.infolist():
                        mode = (entry.external_attr >> 16) & 0o777
                        if mode:
                            (directory / entry.filename).chmod(mode)
    except (tarfile.TarError, zipfile.BadZipFile, ValueError) as error:
        raise RuntimeError('unsafe or invalid tool archive') from error


def resolve_tool(name, tools_root, *, lock_file=LOCK_FILE, env=None):
    if name not in TOOL_COMMANDS:
        raise RuntimeError('unsupported tool: ' + name)
    system, arch = host_platform()
    row = read_lock(lock_file).get((name, system, arch))
    root = Path(tools_root).absolute()
    if row:
        parent = safe_directory(root / name / row[3])
        target = parent / (system + '-' + arch)
        if target.is_symlink():
            raise RuntimeError('tool cache cannot be a symlink')
        if cache_valid(target, row, env):
            return str(target / row[7])
    if name != 'python':
        system_binary = shutil.which(TOOL_COMMANDS[name], path=(env or os.environ).get('PATH'))
        if name == 'jdk' and system_binary:
            # macOS /usr/bin/java is a launcher, not JAVA_HOME/bin/java.
            try:
                properties = run_probe(system_binary, ['-XshowSettings:properties', '-version'], env)
                home = re.search(r'^\s*java.home = (.+)$', properties.stdout + properties.stderr, re.MULTILINE)
                if home:
                    system_binary = str(Path(home[1].strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
            except (OSError, subprocess.SubprocessError):
                system_binary = None
        if system_binary and qualified(system_binary, name, env=env):
            return str(Path(system_binary).absolute())
    if not row:
        raise RuntimeError(f'no locked asset for {name}/{system}/{arch}; qualified system tool required')
    lock = target.with_name(target.name + '.lock')
    try:
        lock.mkdir(mode=0o700)
    except FileExistsError as error:
        raise RuntimeError('tool installation locked; inspect interrupted installer before removing lock') from error
    try:
        (lock / 'pid').write_text(str(os.getpid()), encoding='ascii')
        if cache_valid(target, row, env):
            return str(target / row[7])
        with tempfile.TemporaryDirectory(prefix='.' + target.name + '-', dir=parent) as staging:
            staging = Path(staging)
            asset = staging / 'download'
            download(row[4], asset)
            with asset.open('rb') as stream:
                asset_digest = hashlib.file_digest(stream, 'sha512' if len(row[5]) == 128 else 'sha256').hexdigest()
            if asset_digest != row[5]:
                raise RuntimeError('tool checksum mismatch')
            content = staging / 'content'
            content.mkdir(mode=0o700)
            binary = content / row[7]
            if row[6] == 'raw':
                binary.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(asset, binary)
            else:
                extract(asset, content, row[6])
            if os.name != 'nt':
                binary.chmod(binary.stat().st_mode | 0o100)
            if not qualified(binary, name, row[3], env):
                raise RuntimeError(f'downloaded {name} version/self-check failed ({system}/{arch}; expected {row[3]})')
            (content / '.verified').write_text(row[5] + '\n' + sha256(binary) + '\n', encoding='ascii')
            if target.exists():
                shutil.rmtree(target)
            content.rename(target)
        return str(target / row[7])
    finally:
        shutil.rmtree(lock)


def session_environment(tools_root):
    selected = {}
    environment = dict(os.environ)
    for name in TOOL_COMMANDS:
        selected[name] = resolve_tool(name, tools_root, env=environment)
        if name == 'jdk':
            environment['JAVA_HOME'] = str(Path(selected[name]).resolve().parent.parent)
    # Shared system bin directories may contain older Python/Maven/Terraform.
    # Probe system tools against the original PATH, then keep every selected
    # private directory ahead of it; Python must remain first for old entrypoints.
    root = Path(tools_root).absolute()
    directories = [str(Path(selected['python']).parent)]
    directories.extend(str(Path(path).parent) for path in selected.values()
                       if Path(path).absolute().is_relative_to(root))
    directories.append(str(Path(environment['JAVA_HOME']) / 'bin'))
    environment['PATH'] = os.pathsep.join([*dict.fromkeys(directories), environment.get('PATH', '')])
    selected['env'] = {'AUTOWONDER_PYTHON': selected['python'], 'JAVA_HOME': environment['JAVA_HOME'], 'PATH': environment['PATH'], 'PYTHONUTF8': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
    return selected


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('name', choices=TOOL_COMMANDS, nargs='?')
    parser.add_argument('--emit-env', action='store_true')
    parser.add_argument('--project-root')
    parser.add_argument('--tools-root')
    args = parser.parse_args()
    try:
        if args.project_root:
            root = Path(args.project_root).resolve()
            if root != Path(__file__).resolve().parents[3] or not (root / 'pom.xml').is_file():
                raise RuntimeError('project root must match the canonical skill checkout')
            tools_root = root / 'skills/.autowonder-tools'
        elif args.tools_root:
            tools_root = Path(args.tools_root)
        else:
            raise RuntimeError('--project-root or --tools-root is required')
        if args.emit_env:
            print(json.dumps(session_environment(tools_root), ensure_ascii=False))
        elif args.name:
            print(resolve_tool(args.name, tools_root))
        else:
            raise RuntimeError('tool name or --emit-env is required')
    except (RuntimeError, OSError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
