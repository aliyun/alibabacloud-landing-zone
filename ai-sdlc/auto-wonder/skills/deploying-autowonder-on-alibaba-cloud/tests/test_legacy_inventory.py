"""Execute the actual inventory payload against private local release fixtures."""
import base64
import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


PAYLOAD = Path(__file__).resolve().parents[1] / 'scripts/remote/upgrade_remote.py'


class LegacyInventoryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='库存 中文 ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.commit = 'a' * 40
        self.release = self.root / 'releases' / self.commit[:12]
        self.release.mkdir(parents=True)
        (self.release / 'auto-wonder.jar').write_bytes(b'fixture application')
        (self.release / 'autowonder-migrations.tar.gz').write_bytes(b'fixture migrations')
        try:
            (self.root / 'current').symlink_to(self.release, target_is_directory=True)
        except OSError:
            self.skipTest('inventory fixture requires local directory symlink support')

    def run_payload(self, request):
        encoded = base64.b64encode(json.dumps(request).encode()).decode()
        code = compile(PAYLOAD.read_text().replace('@@REQUEST@@', encoded), str(PAYLOAD), 'exec')
        stdout, stderr = io.StringIO(), io.StringIO()
        # Redirect only the fixed application root; all path operations, hashing
        # and source/target checks execute unchanged against real fixture files.
        path_class = Path
        def local_path(value):
            return self.root if value == '/opt/autowonder' else path_class(value)
        with patch('pathlib.Path', side_effect=local_path), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                exec(code, {'__name__': '__main__'})
                status = 0
            except SystemExit as error:
                status = error.code
        return status, stdout.getvalue(), stderr.getvalue()

    def test_inventory_checks_release_identity_and_actual_artifact_hashes(self):
        status, stdout, stderr = self.run_payload({'operation': 'upgrade-inventory', 'from': self.commit})
        self.assertEqual(status, 0, stderr)
        evidence = dict(line.split('=', 1) for line in stdout.splitlines())
        self.assertEqual(evidence['ACTIVE_COMMIT'], self.commit)
        self.assertEqual(evidence['JAR_SHA256'], hashlib.sha256(b'fixture application').hexdigest())
        self.assertEqual(evidence['MIGRATIONS_SHA256'], hashlib.sha256(b'fixture migrations').hexdigest())

    def test_mutating_operation_names_are_rejected_before_any_command_or_write(self):
        for operation in ('upgrade-backup', 'stage-upgrade', 'database-migrate', 'rolling-upgrade', 'rollback-upgrade'):
            before = {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
            with self.subTest(operation=operation), patch('subprocess.run', side_effect=AssertionError('inventory must not run external commands')):
                status, stdout, stderr = self.run_payload({'operation': operation, 'from': self.commit, 'target': 'b' * 40, 'plan': 'c' * 64})
            self.assertNotEqual(status, 0)
            self.assertIn('Only upgrade-inventory', stderr)
            self.assertEqual(stdout, '')
            after = {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
            self.assertEqual(after, before)
            self.assertEqual((self.root / 'current').resolve(), self.release)

    def test_inventory_rejects_release_mismatch_missing_artifact_and_path_escape(self):
        for mutation in ('wrong-identity', 'missing-artifact', 'outside-releases'):
            with self.subTest(mutation=mutation):
                request = {'operation': 'upgrade-inventory', 'from': self.commit}
                if mutation == 'wrong-identity':
                    request['from'] = 'b' * 40
                elif mutation == 'missing-artifact':
                    (self.release / 'auto-wonder.jar').unlink()
                else:
                    (self.release / 'auto-wonder.jar').write_bytes(b'fixture application')
                    outside = self.root / self.commit[:12]
                    outside.mkdir()
                    (self.root / 'current').unlink()
                    (self.root / 'current').symlink_to(outside, target_is_directory=True)
                status, stdout, stderr = self.run_payload(request)
                self.assertNotEqual(status, 0)
                self.assertEqual(stdout, '')


@unittest.skipUnless(shutil.which('pwsh') or shutil.which('powershell'), 'native PowerShell unavailable')
class LegacyInventoryPowerShellTests(unittest.TestCase):
    def test_mutating_requests_are_rejected_before_cloud_submission(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'inventory-test.ps1'
            common = PAYLOAD.parent.parent / 'windows-upgrade-common.ps1'
            script.write_text("$ErrorActionPreference='Stop'\n. '" + str(common).replace("'", "''") + "'\n" + r"""
function Invoke-AutoWonderCloudCommand { throw 'UNEXPECTED_CLOUD_CALL' }
foreach ($operation in @('upgrade-backup','stage-upgrade','database-migrate','rolling-upgrade','rollback-upgrade')) {
    try {
        Invoke-UpgradePayload -Data @{} -InstanceId 'i-fixture' -Request @{operation=$operation} -ManifestPath 'not-used.json'
        throw 'Mutation was unexpectedly accepted'
    } catch {
        if ($_.Exception.Message -notlike 'Only upgrade-inventory*') { throw }
    }
}
Write-Output 'rejected-all'
""", encoding='utf-8-sig')
            result = subprocess.run([shutil.which('pwsh') or shutil.which('powershell'), '-NoProfile', '-File', str(script)],
                                    env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'), capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), 'rejected-all')


if __name__ == '__main__':
    unittest.main()
