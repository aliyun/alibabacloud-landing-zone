"""CLI diagnostics are untrusted: never publish signed requests on failure."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
PWSH = os.environ.get('AUTOWONDER_TEST_PWSH') or shutil.which('pwsh') or shutil.which('powershell')
ERRORS = (
    'Post "https://ecs.example.invalid/?SecurityToken=FAKE_RAW_TOKEN&Signature=FAKE_SIGNATURE": no such host',
    'Get "https://ecs.example.invalid/?SecurityToken=FAKE%2FTOKEN%2BENCODED&Signature=FAKE%3DSIGNATURE": timeout',
    'ErrorCode: Forbidden\nMessage: ordinary permission denied',
)


@unittest.skipUnless(shutil.which('bash'), 'bash required')
class CloudCliOutputTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='aw-cloud-cli-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        stub = self.root / 'aliyun'
        stub.write_text('#!' + sys.executable + '\n'
                        'import os, sys\n'
                        'assert "auto-wonder" in sys.argv\n'
                        'sys.stdout.write(os.environ["FAKE_STDOUT"])\n'
                        'sys.stderr.write(os.environ["FAKE_STDERR"])\n'
                        'sys.exit(int(os.environ["FAKE_EXIT"]))\n')
        stub.chmod(0o700)

    def run_cli(self, stdout, stderr, status, command='aliyun_cli ecs RunCommand'):
        return subprocess.run(['bash', '-c', 'source "$1"; ' + command,
                               '_', str(SCRIPTS / 'lib.sh')],
                              capture_output=True, text=True, timeout=15,
                              env=dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                                       FAKE_STDOUT=stdout, FAKE_STDERR=stderr, FAKE_EXIT=str(status)))

    def test_failed_requests_hide_both_streams_and_preserve_native_exit(self):
        for message in ERRORS:
            with self.subTest(message_kind=ERRORS.index(message)):
                result = self.run_cli(message, message, 37)
                self.assertEqual(37, result.returncode)
                self.assertEqual('', result.stdout)
                self.assertIn('Alibaba Cloud API request failed', result.stderr)
                self.assertIn('37', result.stderr)
                self.assertNotIn(message, result.stderr)
                self.assertNotIn('FAKE', result.stderr)
                self.assertNotIn('https://', result.stderr)

    def test_success_preserves_business_json_and_hides_stderr(self):
        response = '{\n  "InvokeId": "t-fixture", "Output": "中文", "Token": "business-field"\n}\n'
        result = self.run_cli(response, ERRORS[1], 0)
        self.assertEqual(0, result.returncode)
        self.assertEqual(json.loads(response), json.loads(result.stdout))
        self.assertEqual('', result.stderr)

    def test_transient_identity_failure_does_not_request_oauth(self):
        result = self.run_cli('', 'ErrorCode: Throttling', 37,
                              'CLOUD_PROFILE=auto-wonder; ensure_alicloud_profile_identity cn-hangzhou')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('category=transient', result.stderr)
        self.assertNotIn('OAuth', result.stderr)

    def test_structured_request_id_is_retained_without_raw_message(self):
        request_id = '12345678-1234-1234-1234-123456789abc'
        result = self.run_cli('', json.dumps({'Code': 'Forbidden', 'RequestId': request_id,
                              'Message': 'FAKE_SECRET'}), 37)
        self.assertIn('requestId=' + request_id, result.stderr)
        self.assertNotIn('FAKE_SECRET', result.stderr)

    def test_permission_failure_keeps_safe_classification_only(self):
        result = self.run_cli('', 'ErrorCode: Forbidden\nMessage: https://example.invalid/?Secret=FAKE', 37)
        self.assertIn('category=permission', result.stderr)
        self.assertNotIn('FAKE', result.stderr)
        self.assertNotIn('https://', result.stderr)

    def test_identity_and_failed_oauth_do_not_bypass_error_boundary(self):
        result = self.run_cli('', 'ErrorCode: InvalidSecurityToken.Expired', 37,
                              'CLOUD_PROFILE=auto-wonder; ensure_alicloud_profile_identity cn-hangzhou')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertNotIn('FAKE', result.stderr)
        self.assertNotIn('https://', result.stderr)
        self.assertIn('OAuth login failed', result.stderr)

    def test_python_identity_boundary_hides_signed_errors(self):
        for message in ERRORS:
            result = subprocess.run([sys.executable, '-B', '-c',
                'from operations_oss import OssStore, StoreError\n'
                'try: OssStore("cn-hangzhou").identity()\n'
                'except StoreError as error: print(error); raise SystemExit(1)'],
                cwd=SCRIPTS, capture_output=True, text=True, timeout=15,
                env=dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                         FAKE_STDOUT=message, FAKE_STDERR=message, FAKE_EXIT='37'))
            self.assertEqual(1, result.returncode)
            self.assertIn('identity is unavailable', result.stdout)
            self.assertNotIn('FAKE', result.stdout + result.stderr)
            self.assertNotIn('https://', result.stdout + result.stderr)

    @unittest.skipUnless(PWSH, 'PowerShell runner unavailable; native Windows remains unverified')
    def test_powershell_empty_session_is_a_credential_failure(self):
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        script = self.root / 'empty-session.ps1'
        script.write_text("$ErrorActionPreference='Stop'\n. " + quote(SCRIPTS / 'windows/lib.ps1') + '\n'
            "function Get-AliyunProfile { return @{name='auto-wonder'} }\n"
            "try { Import-AliyunCredential -Profile auto-wonder -Region cn-hangzhou; exit 8 }\n"
            "catch { if ($_.Exception.Data['CloudCategory'] -eq 'credential') { exit 0 }; exit 9 }\n")
        result = subprocess.run([PWSH, '-NoProfile', '-File', str(script)],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode)

    @unittest.skipUnless(PWSH, 'PowerShell runner unavailable; native Windows remains unverified')
    def test_powershell_oauth_hides_native_stderr(self):
        (self.root / 'configure').write_text(
            'import os, sys\nsys.stderr.write(os.environ["FAKE_STDERR"])\nsys.exit(37)\n')
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        script = self.root / 'oauth.ps1'
        script.write_text("$ErrorActionPreference='Stop'\n. " + quote(SCRIPTS / 'windows/lib.ps1') + '\n'
            'function Get-AliyunExecutable { return ' + quote(sys.executable) + ' }\n'
            "function Import-AliyunCredential { $e=[Exception]::new('fixture session expired'); $e.Data['CloudCategory']='credential'; throw $e }\n"
            "Ensure-AutoWonderAliyunProfile -Region cn-hangzhou\n")
        result = subprocess.run([PWSH, '-NoProfile', '-File', str(script)], cwd=self.root,
            capture_output=True, text=True, timeout=30, env=dict(os.environ, FAKE_STDERR=ERRORS[1]))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('OAuth login failed', result.stderr)
        self.assertNotIn('FAKE', result.stdout + result.stderr)
        self.assertNotIn('https://', result.stdout + result.stderr)

    @unittest.skipUnless(PWSH, 'PowerShell runner unavailable; native Windows remains unverified')
    def test_powershell_native_boundary_hides_signed_errors_and_keeps_json(self):
        # Run a real child process through the production native adapter.
        (self.root / 'fake_cloud_cli.py').write_text(
            'import os, sys\n'
            'sys.stdout.write(os.environ["FAKE_STDOUT"])\n'
            'sys.stderr.write(os.environ["FAKE_STDERR"])\n'
            'sys.exit(int(os.environ["FAKE_EXIT"]))\n')
        script = self.root / 'probe.ps1'
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        script.write_text("$ErrorActionPreference='Stop'\n. " + quote(SCRIPTS / 'windows/lib.ps1') + '\n'
            'function Get-AliyunExecutable { return ' + quote(sys.executable) + ' }\n'
            "Invoke-AliyunFlat -Product '-c' -Action 'import fake_cloud_cli' -Profile 'auto-wonder'\n")
        for status in (37, 0):
            response = ERRORS[0] if status else '{"InvokeId":"t-fixture","Output":"中文"}'
            result = subprocess.run([PWSH, '-NoProfile', '-File', str(script)], cwd=self.root,
                capture_output=True, text=True, timeout=30,
                env=dict(os.environ, FAKE_STDOUT=response, FAKE_STDERR=ERRORS[1], FAKE_EXIT=str(status)))
            if status:
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Alibaba Cloud API request failed', result.stderr)
            else:
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(json.loads(response), json.loads(result.stdout))
            self.assertNotIn('FAKE', result.stdout + result.stderr)
            self.assertNotIn('https://', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
