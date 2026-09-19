"""Offline checks for the protected environment's stage/activation boundary."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import test_windows_upgrade_execution as existing

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
GENERATION = "985bc0a7-5abf-4fc7-a612-2549c5a7848d"


class EnvironmentCheckpointTests(unittest.TestCase):
    def test_plan_fingerprint_binds_generation_runtime_and_environment(self):
        spec = importlib.util.spec_from_file_location('checkpoint_plan', SCRIPTS / 'upgrade_plan.py')
        policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(policy)
        data = {'upgrade': {'keyGenerationId': GENERATION, 'targetRecommendedRuntimeVersion': '1.2.3',
                            'environmentPlanSha256': 'a' * 64, 'environmentSha256': 'a' * 64}}
        before = policy.fingerprint(data)
        for key in tuple(data['upgrade']):
            changed = json.loads(json.dumps(data))
            changed['upgrade'][key] = 'changed'
            self.assertNotEqual(before, policy.fingerprint(changed), key)

    def test_planning_requires_generation_before_writing_candidate(self):
        spec = importlib.util.spec_from_file_location('checkpoint_candidate', SCRIPTS / 'upgrade_plan.py')
        policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(policy)
        original = self.env.read_text()
        self.env.write_text(original.replace('AUTOWONDER_SECRET_KEY_GENERATION_ID=' + GENERATION + '\n', ''))
        before = self.env.read_bytes()
        with self.assertRaises(policy.PlanError):
            policy.candidate_env(self.env, '2.0.0')
        self.assertEqual(before, self.env.read_bytes())
        self.env.write_text(original)
        values, digest = policy.candidate_env(self.env, '2.0.0')
        self.assertEqual(GENERATION, values['AUTOWONDER_SECRET_KEY_GENERATION_ID'])
        self.assertEqual(hashlib.sha256(self.env.read_bytes()).hexdigest(), digest)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = (SCRIPTS / "remote/upgrade_remote.py").read_text()
        source = source.replace('json.loads(base64.b64decode("@@REQUEST@@"))', '{}')
        source = source[:source.index('\ntry:\n    require(re.fullmatch')]
        self.ns = {}
        exec(compile(source, "upgrade_remote.py", "exec"), self.ns)
        self.env = self.root / "candidate.env"
        self.env.write_text("AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=1.2.3\n"
                            "AUTOWONDER_SECRET_KEY_GENERATION_ID=" + GENERATION + "\n"
                            "AUTOWONDER_SECRET_MASTER_KEY=protected-canary\n")
        self.sha = hashlib.sha256(self.env.read_bytes()).hexdigest()
        self.ns.update(ENV=self.env, APP=self.root, REQUEST={
            "runtime": "1.2.3", "keyGenerationId": GENERATION, "envSha": self.sha,
            "target": "b" * 40, "backupSha": "c" * 64,
        })

    def test_environment_checkpoint_rejects_tamper_without_sensitive_error(self):
        self.assertIn("verify_environment", self.ns)
        self.ns["verify_environment"](self.env)
        self.env.write_text(self.env.read_text() + "TAMPER=yes\n")
        with self.assertRaises(RuntimeError) as error:
            self.ns["verify_environment"](self.env)
        self.assertNotIn(self.sha, str(error.exception))
        self.assertNotIn("protected-canary", str(error.exception))

    def test_environment_checkpoint_binds_runtime_and_generation(self):
        self.assertIn("verify_environment", self.ns)
        for key in ("runtime", "keyGenerationId"):
            with self.subTest(key=key):
                previous = self.ns["REQUEST"][key]
                self.ns["REQUEST"][key] = "other"
                with self.assertRaises(RuntimeError):
                    self.ns["verify_environment"](self.env)
                self.ns["REQUEST"][key] = previous

    def test_rollout_rechecks_environment_after_activation(self):
        target = self.root / "releases" / ("b" * 12)
        target.mkdir(parents=True)
        jar = target / "auto-wonder.jar"
        jar.write_text("jar")
        unit = self.root / "unit"
        unit.write_text("unit")
        self.ns["UNIT"] = unit
        self.ns["REQUEST"].update(jarSha=hashlib.sha256(jar.read_bytes()).hexdigest(),
                                  unitSha=hashlib.sha256(unit.read_bytes()).hexdigest())
        self.ns["verify_backup"] = lambda *args: None
        self.ns["activate"] = lambda target: self.env.write_text("tampered during restart")
        with self.assertRaises(RuntimeError):
            self.ns["rollout"]()

    def test_stage_detects_tamper_after_environment_install(self):
        fixture = existing.WindowsUpgradeExecutionTests()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        request = fixture.prepare_stage()
        payload = SCRIPTS / 'remote/upgrade_remote.py'
        original = Path.read_text

        def tampered_source(path, *args, **kwargs):
            source = original(path, *args, **kwargs)
            if path == payload:
                source = source.replace('install_file(protected_candidate, ENV, 0o640, "autowonder")',
                    'install_file(protected_candidate, ENV, 0o640, "autowonder")\n'
                    '        ENV.write_text("tampered after install")')
            return source

        with patch.object(Path, 'read_text', tampered_source):
            result = fixture.run_payload('stage-upgrade.sh', request, mock_system=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(request['envSha'], result.stdout + result.stderr)
        self.assertNotIn('STAGED_COMMIT', result.stdout)

    def test_admin_migration_blocks_without_admin_and_allows_existing_admin(self):
        for count in ('0', '1'):
            with self.subTest(count=count):
                fixture = existing.WindowsUpgradeExecutionTests()
                fixture.setUp()
                self.addCleanup(fixture.tearDown)
                fixture.mock_mysql()
                mysql = fixture.bin / 'mysql'
                text = mysql.read_text().replace("if sql.startswith('SELECT CONCAT'):",
                    "if sql.startswith('SELECT COUNT(*) FROM `user`'):\n        print(" + repr(count) + ")\n    elif sql.startswith('SELECT CONCAT'):")
                mysql.write_text(text)
                migrations = fixture.migrations()
                directory = fixture.app / 'releases' / fixture.target[:12] / 'migration'
                target = directory / 'V067__platform_admin_init.sql'
                target.write_text('SELECT 67;')
                migration = {'version': 67, 'file': 'docs/migration/' + target.name,
                             'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
                result = fixture.run_payload('database-migrate.sh', {'migrations': [migration]})
                if count == '0':
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse((fixture.root / 'executed.log').exists())
                    self.assertFalse((fixture.root / 'ledger.json').exists())
                else:
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual('SELECT 67;\n', (fixture.root / 'executed.log').read_text())

    def test_posix_stage_and_rollout_verify_installed_environment(self):
        stage = (SCRIPTS / "internal/release-transfer.sh").read_text()
        install = stage.index('mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env')
        self.assertIn('installed environment checkpoint mismatch', stage[install:])
        rollout = (SCRIPTS / "internal/operations.sh").read_text().split('  rolling-upgrade)')[1]
        restart = rollout.index('systemctl restart autowonder.service')
        self.assertIn('installed environment checkpoint mismatch', rollout[:restart])
        self.assertIn('installed environment checkpoint mismatch', rollout[restart:])

    def test_posix_installed_hash_checks_fail_closed_without_hash_output(self):
        for filename in ('internal/operations.sh', 'internal/release-transfer.sh'):
            checks = [line.strip() for line in (SCRIPTS / filename).read_text().splitlines()
                      if 'installed environment checkpoint mismatch' in line]
            self.assertGreaterEqual(len(checks), 2)
            for line in checks:
                script = line.replace('\\$', '$').replace('\\"', '"')
                script = script.replace('/etc/autowonder/autowonder.env', str(self.env))
                script = script.replace('$env_hash', self.sha).replace('$expected_env', self.sha)
                before = self.env.read_bytes()
                self.assertEqual(0, subprocess.run(['bash', '-c', script], capture_output=True).returncode)
                self.env.write_bytes(before + b'TAMPER=yes\n')
                result = subprocess.run(['bash', '-c', script], capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn(self.sha, result.stdout + result.stderr)
                self.env.write_bytes(before)

    def test_sanitized_report_excludes_environment_hashes_and_master_key(self):
        manifest = self.root / 'manifest.json'
        manifest.write_text(json.dumps({'runtimeConfig': {'envSha256': self.sha},
            'upgrade': {'environmentCandidateSha256': self.sha, 'environmentSha256': self.sha,
                        'environmentPlanSha256': self.sha},
            'AUTOWONDER_SECRET_MASTER_KEY': 'protected-canary'}))
        output = self.root / 'report.json'
        result = subprocess.run(['bash', str(SCRIPTS / 'sanitize-evidence.sh'), '--input',
                                 str(manifest), '--output', str(output)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn(self.sha, output.read_text())
        self.assertNotIn('protected-canary', output.read_text())

    def test_text_report_excludes_protected_checkpoint_lines(self):
        source = self.root / 'evidence.txt'
        source.write_text('environmentPlanSha256=' + self.sha + '\n'
                          'AUTOWONDER_SECRET_MASTER_KEY=protected-canary\nSTATUS=passed\n')
        report = self.root / 'sanitized.txt'
        result = subprocess.run(['bash', str(SCRIPTS / 'sanitize-evidence.sh'), '--input',
                                 str(source), '--output', str(report)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('STATUS=passed\n', report.read_text())

    def test_admin_migration_requires_existing_effective_admin_on_both_routes(self):
        for filename in ('internal/operations.sh', 'remote/upgrade_remote.py'):
            source = (SCRIPTS / filename).read_text()
            self.assertIn('platform_admin_init.sql', source)
            self.assertIn('is_deleted = 0 AND is_admin = 1', source)
            self.assertIn('existing system administrator', source)


if __name__ == "__main__":
    unittest.main()
