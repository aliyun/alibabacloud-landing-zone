"""Offline behavior tests: downloads and executables are local fixtures only."""
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import urllib.error
import zipfile
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))


def executable(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('#!/bin/sh\n' + body + '\n')
    path.chmod(0o755)
    return path


def archive(path, entry, text):
    with tarfile.open(path, 'w:gz') as out:
        value = text.encode()
        member = tarfile.TarInfo(entry)
        member.size = len(value)
        member.mode = 0o755
        out.addfile(member, io.BytesIO(value))
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ToolDownloadTests(unittest.TestCase):
    def setUp(self):
        self.module = importlib.import_module('tool_runtime')
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.target = Path(temporary.name) / 'download'
        self.url = 'https://user:SECRET_PASSWORD@downloads.example.test/asset?token=SECRET_TOKEN'

    def response(self, content, url='https://downloads.example.test/asset'):
        response = io.BytesIO(content)
        response.geturl = lambda: url
        return response

    def test_dns_failure_retries_once_with_same_timeout_then_succeeds(self):
        error = urllib.error.URLError('DNS failure for ' + self.url)
        with patch.object(self.module.urllib.request, 'urlopen', side_effect=[error, self.response(b'verified later')]) as opener:
            try:
                self.module.download(self.url, self.target)
            except Exception as failure:
                self.fail('transient DNS error did not recover: ' + type(failure).__name__)
        self.assertEqual(self.target.read_bytes(), b'verified later')
        self.assertEqual(opener.call_count, 2)
        self.assertEqual([call.kwargs['timeout'] for call in opener.call_args_list], [60, 60])

    def test_repeated_network_failure_is_bounded_and_redacted(self):
        error = urllib.error.URLError('DNS failure for ' + self.url)
        with patch.object(self.module.urllib.request, 'urlopen', side_effect=error) as opener:
            with self.assertRaises(Exception) as raised:
                self.module.download(self.url, self.target)
        self.assertEqual(opener.call_count, 2)
        message = str(raised.exception)
        self.assertIn('download', message)
        self.assertIn('downloads.example.test', message)
        for secret in ('SECRET_PASSWORD', 'SECRET_TOKEN', 'user:'):
            self.assertNotIn(secret, message)
        self.assertTrue(raised.exception.__suppress_context__, 'raw URL exception must not appear in traceback')

    def test_http_rejection_does_not_retry_or_expose_url(self):
        error = urllib.error.HTTPError(self.url, 403, 'SECRET_TOKEN', {}, None)
        with patch.object(self.module.urllib.request, 'urlopen', side_effect=error) as opener:
            with self.assertRaises(Exception) as raised:
                self.module.download(self.url, self.target)
        self.assertEqual(opener.call_count, 1)
        self.assertIn('downloads.example.test', str(raised.exception))
        self.assertNotIn('SECRET', str(raised.exception))

    def test_non_https_redirect_never_retries_or_writes_payload(self):
        with patch.object(self.module.urllib.request, 'urlopen', return_value=self.response(b'rejected', 'http://downloads.example.test/asset')) as opener:
            with self.assertRaises(RuntimeError):
                self.module.download(self.url, self.target)
        self.assertEqual(opener.call_count, 1)
        self.assertFalse(self.target.exists())

    def test_interrupted_response_restarts_file_without_partial_prefix(self):
        first = self.response(b'')
        first.read = unittest.mock.Mock(side_effect=[b'partial prefix', ConnectionResetError('SECRET_TOKEN')])
        with patch.object(self.module.urllib.request, 'urlopen', side_effect=[first, self.response(b'complete')]) as opener:
            try:
                self.module.download(self.url, self.target)
            except Exception as failure:
                self.fail('interrupted download did not recover: ' + type(failure).__name__)
        self.assertEqual(opener.call_count, 2)
        self.assertEqual(self.target.read_bytes(), b'complete')


@unittest.skipIf(os.name == 'nt', 'POSIX fake executables; Windows has native CMD probe coverage')
class ToolRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((SCRIPTS / 'tool_runtime.py').is_file(), 'shared resolver is not implemented')
        self.module = importlib.import_module('tool_runtime')
        self.temp = tempfile.TemporaryDirectory(prefix='运行 空格 ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.tools = self.root / 'skills' / '.autowonder-tools'
        self.lock = self.root / 'runtime-lock.tsv'
        self.asset = self.root / 'archive.tar.gz'
        self.digest = archive(self.asset, 'bin/terraform', '#!/bin/sh\nprintf "Terraform v1.9.8\\n"\n')
        self.lock.write_text('terraform\tdarwin\tarm64\t1.9.8\thttps://releases.example.test/tool.tar.gz\t' + self.digest + '\ttar.gz\tbin/terraform\n')
        self.addCleanup(patch.stopall)
        patch.object(self.module, 'host_platform', return_value=('darwin', 'arm64')).start()
        patch.object(self.module.shutil, 'which', return_value=None).start()
        self.download = patch.object(self.module, 'download', side_effect=lambda url, dest: shutil.copyfile(self.asset, dest)).start()

    def resolve(self):
        return self.module.resolve_tool('terraform', self.tools, lock_file=self.lock)

    def test_python_probe_rejects_missing_required_stdlib(self):
        probe = self.root / 'python-fixture'
        probe.write_text('#!' + sys.executable + '\nimport builtins,sys\n'
            'original=builtins.__import__\n'
            'def guarded(name,*args,**kwargs):\n'
            '    if name == "tarfile": raise ModuleNotFoundError("fixture missing stdlib")\n'
            '    return original(name,*args,**kwargs)\n'
            'builtins.__import__=guarded\n'
            'exec(sys.argv[sys.argv.index("-c")+1])\n')
        probe.chmod(0o755)
        arch = 'arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x86_64'
        with patch.object(self.module, 'host_platform', return_value=('darwin', arch)):
            self.assertFalse(self.module.qualified(probe, 'python'))

    def test_cli_unicode_path_and_environment_ignore_legacy_stdout_encoding(self):
        for arguments in (['python', '--tools-root', str(self.tools)],
                          ['--emit-env', '--tools-root', str(self.tools)]):
            code = ('import sys,json; sys.path.insert(0,' + repr(str(SCRIPTS)) + '); '
                    'import tool_runtime as runtime; '
                    'runtime.resolve_tool=lambda *a,**k: "C:/中文工具/python.exe"; '
                    'runtime.session_environment=lambda *a: {"env":{"PATH":"C:/中文工具"}}; '
                    'sys.argv=["tool_runtime",*json.loads(sys.argv[1])]; runtime.main()')
            result = subprocess.run([sys.executable, '-B', '-c', code, json.dumps(arguments)],
                                    capture_output=True, env=dict(os.environ, PYTHONIOENCODING='ascii'))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('中文工具', result.stdout.decode('utf-8'))

    def test_sha512_lock_checks_archive_before_extract(self):
        digest = hashlib.sha512(self.asset.read_bytes()).hexdigest()
        self.lock.write_text(self.lock.read_text().replace(self.digest, digest))
        self.assertTrue(Path(self.resolve()).is_file())

    def test_zip_preserves_executable_permission(self):
        with zipfile.ZipFile(self.asset, 'w') as archive_file:
            member = zipfile.ZipInfo('bin/terraform')
            member.external_attr = 0o100755 << 16
            archive_file.writestr(member, '#!/bin/sh\nprintf "Terraform v1.9.8\\n"\n')
        digest = hashlib.sha256(self.asset.read_bytes()).hexdigest()
        self.lock.write_text(self.lock.read_text().replace(self.digest, digest).replace('\ttar.gz\t', '\tzip\t'))
        self.assertTrue(os.access(self.resolve(), os.X_OK))

    def test_java_without_matching_compiler_is_rejected(self):
        java = executable(self.root / 'jdk/bin/java', "echo 'openjdk version \"21.0.10\"' >&2")
        self.assertFalse(self.module.qualified(java, 'jdk'))
        executable(java.parent / 'javac', 'echo "javac 17.0.1"')
        self.assertFalse(self.module.qualified(java, 'jdk'))
        executable(java.parent / 'javac', 'echo "javac 21.0.10"')
        self.assertTrue(self.module.qualified(java, 'jdk'))

    def test_install_then_reuse_without_download(self):
        result = self.resolve()
        self.assertEqual(Path(result), self.tools / 'terraform/1.9.8/darwin-arm64/bin/terraform')
        self.assertTrue(Path(result).is_absolute())
        self.download.side_effect = AssertionError('cached tool must not download')
        self.assertEqual(self.resolve(), result)

    def test_qualified_system_is_used_before_download(self):
        binary = executable(self.root / 'system terraform', 'printf "Terraform v1.10.5\\n"')
        self.module.shutil.which.return_value = str(binary)
        self.assertEqual(self.resolve(), str(binary))
        self.download.assert_not_called()

    def test_bad_cache_does_not_execute_and_recovers(self):
        result = Path(self.resolve())
        result.write_text('#!/bin/sh\ntouch "' + str(self.root / 'tampered-ran') + '"\nprintf "Terraform v1.9.8\\n"\n')
        self.assertEqual(Path(self.resolve()), result)
        self.assertFalse((self.root / 'tampered-ran').exists())
        self.assertEqual(self.download.call_count, 2)

    def test_checksum_failure_never_activates(self):
        self.lock.write_text(self.lock.read_text().replace(self.digest, '0' * 64))
        with self.assertRaisesRegex(RuntimeError, 'checksum'):
            self.resolve()
        self.assertFalse((self.tools / 'terraform/1.9.8/darwin-arm64').exists())

    def test_interrupted_download_releases_lock_for_next_attempt(self):
        self.download.side_effect = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.resolve()
        self.download.side_effect = lambda url, dest: shutil.copyfile(self.asset, dest)
        self.assertTrue(Path(self.resolve()).is_file())

    def test_busy_install_does_not_download(self):
        lock = self.tools / 'terraform/1.9.8/darwin-arm64.lock'
        lock.mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, 'locked'):
            self.resolve()
        self.download.assert_not_called()

    def test_archive_traversal_is_rejected(self):
        digest = archive(self.asset, '../escape', 'oops')
        self.lock.write_text(self.lock.read_text().replace(self.digest, digest))
        with self.assertRaisesRegex(RuntimeError, 'archive'):
            self.resolve()
        self.assertFalse((self.tools / 'terraform/1.9.8/escape').exists())

    def test_session_environment_selects_jdk_before_maven_without_mutating_parent(self):
        self.assertTrue(hasattr(self.module, 'session_environment'), 'session environment is not implemented')
        paths = {name: str(self.tools / name / 'bin' / command) for name, command in
                 [('python', 'python3'), ('jdk', 'java'), ('maven', 'mvn'), ('terraform', 'terraform'),
                  ('aliyun', 'aliyun'), ('ossutil', 'ossutil'), ('jq', 'jq')]}
        original = dict(os.environ)
        with patch.object(self.module, 'resolve_tool', side_effect=lambda name, root, **kwargs: paths[name]):
            result = self.module.session_environment(self.tools)
        self.assertEqual(result['env']['JAVA_HOME'], str(self.tools / 'jdk'))
        self.assertEqual(result['env']['AUTOWONDER_PYTHON'], paths['python'])
        self.assertIn(str(self.tools / 'maven/bin'), result['env']['PATH'])
        for name in ('aliyun', 'ossutil', 'jq'):
            self.assertEqual(result[name], paths[name])
            self.assertIn(str(Path(paths[name]).parent), result['env']['PATH'])
        self.assertEqual(dict(os.environ), original)

    def test_cloud_tools_use_only_local_version_probes(self):
        for name, argument, good, bad in [
                ('aliyun', 'version', '3.4.11', '2.9.9'),
                ('ossutil', 'version', 'ossutil version: 2.4.0', 'ossutil version: 1.7.19'),
                ('jq', '--version', 'jq-1.8.2', 'jq-1.6')]:
            with self.subTest(tool=name):
                binary = executable(self.root / name, f'[ "$#" = 1 ] && [ "$1" = "{argument}" ] || exit 96\necho "{good}"')
                self.assertTrue(self.module.qualified(binary, name))
                executable(binary, f'echo "{bad}"')
                self.assertFalse(self.module.qualified(binary, name))
                executable(binary, f'echo "{good}"\nexit 9')
                self.assertFalse(self.module.qualified(binary, name))

    def test_ossutil_v2_publisher_binary_prints_bare_version(self):
        binary = executable(self.root / 'ossutil', '[ "$1" = version ] || exit 96\nprintf "2.4.0\\n"')
        self.assertTrue(self.module.qualified(binary, 'ossutil', '2.4.0'))
        self.assertFalse(self.module.qualified(binary, 'ossutil', '2.4.1'))
        executable(binary, 'printf "1.7.19\\n"')
        self.assertFalse(self.module.qualified(binary, 'ossutil'))
        executable(binary, 'printf "2.4.0\\n"\nexit 1')
        self.assertFalse(self.module.qualified(binary, 'ossutil'))

    def test_cloud_system_tools_are_reused_without_download(self):
        for name, output in [('aliyun', '3.4.11'), ('ossutil', 'ossutil version: 2.4.0'), ('jq', 'jq-1.8.2')]:
            with self.subTest(tool=name):
                binary = executable(self.root / name, 'echo "' + output + '"')
                self.module.shutil.which.return_value = str(binary)
                selected = self.module.resolve_tool(name, self.tools, lock_file=self.lock)
                self.assertEqual(selected, str(binary))
        self.download.assert_not_called()

    def test_raw_jq_fallback_is_verified_executable_and_cached(self):
        self.asset.write_bytes(b'#!/bin/sh\nprintf "jq-1.8.2\\n"\n')
        digest = hashlib.sha256(self.asset.read_bytes()).hexdigest()
        self.lock.write_text('jq\tdarwin\tarm64\t1.8.2\thttps://example.test/jq\t' + digest + '\traw\tbin/jq\n')
        binary = executable(self.root / 'old-jq', 'echo "jq-1.6"')
        self.module.shutil.which.return_value = str(binary)
        selected = self.module.resolve_tool('jq', self.tools, lock_file=self.lock)
        self.assertEqual(Path(selected), self.tools / 'jq/1.8.2/darwin-arm64/bin/jq')
        self.assertTrue(os.access(selected, os.X_OK))
        self.download.side_effect = AssertionError('cached jq must not download')
        self.assertEqual(self.module.resolve_tool('jq', self.tools, lock_file=self.lock), selected)

    def test_raw_checksum_failure_does_not_execute_download(self):
        marker = self.root / 'unverified-ran'
        self.asset.write_text('#!/bin/sh\ntouch "' + str(marker) + '"\necho jq-1.8.2\n')
        self.lock.write_text('jq\tdarwin\tarm64\t1.8.2\thttps://example.test/jq\t' + '0' * 64 + '\traw\tjq\n')
        with self.assertRaisesRegex(RuntimeError, 'checksum'):
            self.module.resolve_tool('jq', self.tools, lock_file=self.lock)
        self.assertFalse(marker.exists())

    def test_cloud_archive_fallbacks_use_locked_executable_layout(self):
        for name, entry, version, output in [('aliyun', 'aliyun', '3.4.11', '3.4.11'),
                ('ossutil', 'ossutil-2.4.0-mac-arm64/ossutil', '2.4.0', 'ossutil version: 2.4.0')]:
            with self.subTest(tool=name):
                digest = archive(self.asset, entry, '#!/bin/sh\necho "' + output + '"\n')
                fmt = 'tar.gz'
                if name == 'ossutil':
                    # Some publisher ZIP files carry no POSIX execute bit.
                    with zipfile.ZipFile(self.asset, 'w') as package:
                        package.writestr(entry, '#!/bin/sh\necho "' + output + '"\n')
                    digest = hashlib.sha256(self.asset.read_bytes()).hexdigest()
                    fmt = 'zip'
                self.lock.write_text(f'{name}\tdarwin\tarm64\t{version}\thttps://example.test/tool\t{digest}\t{fmt}\t{entry}\n')
                selected = self.module.resolve_tool(name, self.tools, lock_file=self.lock)
                self.assertTrue(Path(selected).is_file())
                self.assertEqual(Path(selected).relative_to(self.tools).as_posix(), f'{name}/{version}/darwin-arm64/{entry}')

    def test_unlocked_platform_never_downloads(self):
        self.module.host_platform.return_value = ('linux', 'arm64')
        with self.assertRaisesRegex(RuntimeError, 'locked asset'):
            self.resolve()
        self.download.assert_not_called()


@unittest.skipIf(os.name == 'nt', 'POSIX native bootstrap; Windows runner has separate tests')
class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((SCRIPTS / 'runtime-bootstrap.sh').is_file(), 'native bootstrap is not implemented')
        self.temp = tempfile.TemporaryDirectory(prefix='冷启动 空格 ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.scripts = self.root / 'skills/deploying-autowonder-on-alibaba-cloud/scripts'
        self.scripts.mkdir(parents=True)
        (self.root / 'pom.xml').write_text('<project/>')
        shutil.copyfile(SCRIPTS / 'runtime-bootstrap.sh', self.scripts / 'runtime-bootstrap.sh')
        self.asset = self.root / 'python.tar.gz'
        self.digest = archive(self.asset, 'python/bin/python3', '#!/bin/sh\nprintf "3.13.11\\n"\n')
        self.system, self.arch = ('darwin' if sys.platform == 'darwin' else 'linux'), ('arm64' if platform.machine() in ('arm64', 'aarch64') else 'x86_64')
        (self.scripts / 'runtime-lock.tsv').write_text(f'python\t{self.system}\t{self.arch}\t3.13.11\thttps://example.test/python.tar.gz\t{self.digest}\ttar.gz\tpython/bin/python3\n')
        self.bin = self.root / 'fake commands'
        self.bin.mkdir()
        executable(self.bin / 'curl', 'while [ "$#" -gt 0 ]; do if [ "$1" = "--output" ]; then shift; cp "$FAKE_ARCHIVE" "$1"; exit; fi; shift; done; exit 99')
        # Cold start must never call system Python or jq.
        for name in ('python', 'python3', 'jq'):
            executable(self.bin / name, 'exit 97')
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], FAKE_ARCHIVE=str(self.asset))

    def run_bootstrap(self, *args):
        return subprocess.run(['/bin/sh', str(self.scripts / 'runtime-bootstrap.sh'), *args], env=self.env, text=True, capture_output=True)

    def test_native_selfchecks_reject_missing_core_stdlib(self):
        aliases = "('arm64', 'aarch64')" if self.arch == 'arm64' else "('x86_64', 'amd64')"
        for script in (SCRIPTS / 'runtime-bootstrap.sh', SCRIPTS / 'windows/runtime-bootstrap.ps1'):
            expression = re.search(r'^\$?selfcheck\s*=\s*"(.+)"$', script.read_text(), re.M).group(1)
            expression = expression.replace('$aliases', aliases)
            for missing in ('argparse', 'json', 'tarfile', 'urllib.request', 'subprocess', 'pathlib', 'ipaddress', 'shutil'):
                with self.subTest(script=script.name, missing=missing):
                    code = ('import builtins\noriginal=builtins.__import__\n'
                            'def guarded(name,*args,**kwargs):\n'
                            '    if name == ' + repr(missing) + ': raise ModuleNotFoundError("fixture missing stdlib")\n'
                            '    return original(name,*args,**kwargs)\n'
                            'builtins.__import__=guarded\n' + expression)
                    result = subprocess.run([sys.executable, '-I', '-c', code], capture_output=True)
                    self.assertNotEqual(0, result.returncode, 'incomplete runtime passed bootstrap self-check')

    def test_cold_start_without_system_python_and_with_unicode_spaces(self):
        result = self.run_bootstrap('--project-root', str(self.root))
        self.assertEqual(result.returncode, 0, result.stderr)
        python = Path(result.stdout.strip())
        self.assertTrue(python.is_file())
        self.assertTrue(str(python).startswith(str(self.root / 'skills/.autowonder-tools/python/3.13.11')))
        executable(self.bin / 'curl', 'exit 96')
        self.assertEqual(self.run_bootstrap().stdout, result.stdout)

    def test_rejects_foreign_project_root(self):
        result = self.run_bootstrap('--project-root', '/tmp')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('project root', result.stderr)

    def test_bad_checksum_never_activates(self):
        self.asset.write_bytes(b'not an archive')
        result = self.run_bootstrap()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('checksum', result.stderr)
        self.assertFalse(any((self.root / 'skills/.autowonder-tools').rglob('python3')))

    def test_busy_lock_fails_closed(self):
        (self.root / f'skills/.autowonder-tools/python/3.13.11/{self.system}-{self.arch}.lock').mkdir(parents=True)
        result = self.run_bootstrap()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('locked', result.stderr)


@unittest.skipUnless(os.name == 'nt', 'requires native Windows; no simulated shell coverage')
class WindowsBootstrapTests(unittest.TestCase):
    def test_native_maven_cmd_path_preserves_spaces_unicode_and_percent(self):
        module = importlib.import_module('tool_runtime')
        with tempfile.TemporaryDirectory(prefix='工具 空格 %PATH% ') as folder:
            binary = Path(folder) / 'mvn.cmd'
            binary.write_text('@echo off\r\necho Apache Maven 3.9.11\r\n', encoding='ascii')
            self.assertTrue(module.qualified(binary, 'maven'))

    def native_shell(self, name):
        shell = shutil.which(name)
        if not shell:
            self.skipTest(name + ' is not installed; native entry unverified')
        return shell

    def bootstrap_fixture(self):
        temporary = tempfile.TemporaryDirectory(prefix='Windows 中文 空格 ')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve()
        scripts = root / 'skills/deploying-autowonder-on-alibaba-cloud/scripts'
        (scripts / 'windows').mkdir(parents=True)
        (root / 'pom.xml').write_text('<project/>')
        for relative in ('windows/runtime-bootstrap.ps1', 'runtime-bootstrap.cmd', 'runtime-bootstrap.sh'):
            shutil.copyfile(SCRIPTS / relative, scripts / relative)
        asset = root / 'python.tar.gz'
        digest = archive(asset, 'python/python.cmd', '@echo off\r\necho 3.13.11\r\n')
        architecture = os.environ.get('PROCESSOR_ARCHITEW6432') or os.environ.get('PROCESSOR_ARCHITECTURE', '')
        if architecture.upper() not in ('ARM64', 'AMD64'):
            self.skipTest('native 64-bit Windows architecture required')
        arch = 'arm64' if architecture.upper() == 'ARM64' else 'x86_64'
        # Loopback also prevents an external download if a warm-dispatch test
        # unexpectedly misses its cache. Cold tests intercept Invoke-WebRequest.
        (scripts / 'runtime-lock.tsv').write_text(
            f'python\twindows\t{arch}\t3.13.11\thttps://127.0.0.1:1/fixture\t{digest}\ttar.gz\tpython/python.cmd\n')
        wrapper = root / 'fixture.ps1'
        wrapper.write_text(
            "function Invoke-WebRequest { param($Uri,$OutFile,$TimeoutSec,[switch]$UseBasicParsing) Copy-Item -LiteralPath $env:FAKE_ARCHIVE -Destination $OutFile }\n"
            "& (Join-Path $PSScriptRoot 'skills/deploying-autowonder-on-alibaba-cloud/scripts/windows/runtime-bootstrap.ps1') -ProjectRoot $PSScriptRoot\n",
            encoding='utf-8-sig')
        windows = Path(os.environ['SystemRoot'])
        path = os.pathsep.join(str(folder) for folder in (
            windows / 'System32', windows, windows / 'System32/WindowsPowerShell/v1.0'))
        env = dict(os.environ, PATH=path, FAKE_ARCHIVE=str(asset))
        for command in ('python', 'python3', 'jq'):
            self.assertIsNone(shutil.which(command, path=path), 'fixture PATH must exclude system ' + command)
        if not shutil.which('tar.exe', path=path):
            self.skipTest('native Windows tar.exe is not installed')
        return root, scripts, asset, wrapper, env

    def run_fixture(self, shell, wrapper, env):
        return subprocess.run([shell, '-NoProfile', '-File', str(wrapper)],
                              env=env, encoding='utf-8', capture_output=True, timeout=60)

    def assert_cold_start_and_bad_checksum(self, shell):
        root, scripts, asset, wrapper, env = self.bootstrap_fixture()
        result = self.run_fixture(shell, wrapper, env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(Path(result.stdout.strip()).is_file())
        cache = root / 'skills/.autowonder-tools'
        self.assertTrue(cache.stat().st_file_attributes & 2, 'Windows cache must be hidden')
        shutil.rmtree(cache)
        asset.write_bytes(b'bad archive')
        result = self.run_fixture(shell, wrapper, env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('checksum', result.stderr)
        self.assertFalse(any(cache.rglob('python.cmd')), 'unverified executable must not activate')

    def test_powershell51_native_cold_start_hidden_cache_and_bad_checksum(self):
        self.assert_cold_start_and_bad_checksum(self.native_shell('powershell.exe'))

    def test_powershell7_native_cold_start_hidden_cache_and_bad_checksum(self):
        self.assert_cold_start_and_bad_checksum(self.native_shell('pwsh.exe'))

    def test_cmd_native_warm_dispatch_unicode_project_without_system_python(self):
        """Real CMD dispatch/cache reuse; the PS fixture alone supplies cold data."""
        shell = self.native_shell('powershell.exe')
        command = self.native_shell('cmd.exe')
        root, scripts, asset, wrapper, env = self.bootstrap_fixture()
        prepared = self.run_fixture(shell, wrapper, env)
        self.assertEqual(prepared.returncode, 0, prepared.stderr)
        asset.unlink()  # Warm dispatch cannot get another fixture download.
        env['BOOTSTRAP_ENTRY'] = str(scripts / 'runtime-bootstrap.cmd')
        env['BOOTSTRAP_ROOT'] = str(root)
        result = subprocess.run('\"' + command + '\" /d /s /c \"\"%BOOTSTRAP_ENTRY%\" --project-root \"%BOOTSTRAP_ROOT%\"\"',
                                env=env, encoding='utf-8', capture_output=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(Path(result.stdout.strip()), Path(prepared.stdout.strip()))

    def test_git_bash_native_warm_dispatch_unicode_project_without_system_python(self):
        """Real Git Bash -> native Windows warm dispatch, never a WSL bash alias."""
        shell = self.native_shell('powershell.exe')
        git = self.native_shell('git.exe')
        git_root = Path(git).resolve().parent.parent
        candidates = [git_root / 'bin/bash.exe', git_root / 'usr/bin/bash.exe']
        on_path = shutil.which('bash.exe')
        if on_path:
            candidates.append(Path(on_path))
        bash = next((path for path in candidates if path.is_file() and
                     ((path.parent / 'cygpath.exe').is_file() or
                      (path.parent.parent / 'usr/bin/cygpath.exe').is_file())), None)
        if bash is None:
            self.skipTest('Git Bash with cygpath is not installed; WSL does not cover this entry')
        root, scripts, asset, wrapper, env = self.bootstrap_fixture()
        native_bin = bash.parent if (bash.parent / 'cygpath.exe').is_file() else bash.parent.parent / 'usr/bin'
        env['PATH'] = os.pathsep.join((str(native_bin), str(bash.parent), env['PATH']))
        for name in ('python', 'python3', 'jq'):
            self.assertIsNone(shutil.which(name, path=env['PATH']))
        prepared = self.run_fixture(shell, wrapper, env)
        self.assertEqual(prepared.returncode, 0, prepared.stderr)
        asset.unlink()
        result = subprocess.run([str(bash), '--noprofile', '--norc', '-c',
                                 'root=$(cygpath -u "$1") && exec sh "$root/skills/deploying-autowonder-on-alibaba-cloud/scripts/runtime-bootstrap.sh" --project-root "$root"',
                                 '_', str(root)], env=env, encoding='utf-8', capture_output=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(Path(result.stdout.strip()), Path(prepared.stdout.strip()))


if __name__ == '__main__':
    unittest.main()
