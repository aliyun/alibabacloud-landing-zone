import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
UPGRADE = SCRIPTS.parents[1] / 'upgrading-autowonder-on-alibaba-cloud' / 'scripts'

class OperationsHooksTests(unittest.TestCase):
    def test_failed_checkpoint_stops_next_shell_action(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'operationsStore': {'revision': 'old'}}))
            fake = root / 'python3'
            fake.write_text('#!/bin/sh\ncase "$2" in checkpoint) exit 47;; esac\nexit 0\n')
            fake.chmod(0o700)
            result = subprocess.run(['bash', '-c', 'source "$1"; atomic_jq "$2" ".status=\\\"ready\\\""; touch "$3"', '_', str(SCRIPTS / 'lib.sh'), str(manifest), str(root / 'unsafe')], env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']), capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / 'unsafe').exists())
            self.assertEqual(json.loads(manifest.read_text())['status'], 'ready')

    def test_legacy_shell_update_does_not_require_cloud(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            manifest.write_text('{}')
            result = subprocess.run(['bash', '-c', 'source "$1"; atomic_jq "$2" ".status=\\\"ready\\\""', '_', str(SCRIPTS / 'lib.sh'), str(manifest)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(manifest.read_text())['status'], 'ready')

    def test_stale_bound_revision_stops_local_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'operationsStore': {'revision': 'old'}}))
            fake = root / 'python3'
            fake.write_text('#!/bin/sh\nexit 47\n')
            fake.chmod(0o700)
            result = subprocess.run(['bash', '-c', 'source "$1"; atomic_jq "$2" ".changed=true"', '_', str(SCRIPTS / 'lib.sh'), str(manifest)], env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']), capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('changed', json.loads(manifest.read_text()))

    def test_bound_recovery_cannot_regenerate_secrets(self):
        for kind in ('terraform', 'runtime'):
            for bootstrap, ready, recorded, allowed in [(True, False, False, True), (True, False, True, False), (False, False, False, False), (True, True, False, False)]:
                with self.subTest(kind=kind, bootstrap=bootstrap, ready=ready, recorded=recorded), tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'manifest.json'
                    path.write_text(json.dumps({'operationsStore': {'bootstrap': bootstrap, 'ready': ready, kind + 'SecretsRecorded': recorded}, 'deployment': {'activeCommit': 'built-but-not-deployed'}}))
                    result = subprocess.run(['bash', '-c', 'source "$1"; require_secret_creation_allowed "$2" "$3"', '_', str(SCRIPTS / 'lib.sh'), str(path), kind], capture_output=True)
                    self.assertEqual(result.returncode == 0, allowed)

    def test_known_invocation_must_finish_before_next_submission(self):
        for scope in ('top', 'upgrade'):
            for status in ('submitted', 'running', 'poll-timeout', 'error', 'timed-out', 'failed', 'finished'):
                with self.subTest(scope=scope, status=status), tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'manifest.json'
                    data = {'operationsStore': {'revision': 'r1'}}
                    target = data if scope == 'top' else data.setdefault('upgrade', {})
                    target['remoteInvocations'] = [{'status': status}]
                    path.write_text(json.dumps(data))
                    result = subprocess.run(['bash', '-c', 'source "$1"; require_remote_submission_settled "$2"', '_', str(SCRIPTS / 'lib.sh'), str(path)], capture_output=True)
                    self.assertEqual(result.returncode == 0, status == 'finished')

    def test_unresolved_migration_and_terraform_block_mutation(self):
        for state in ({'terraform': {'pendingOperation': 'apply'}, 'phase': 'terraform-plan', 'status': 'ready'}, {'operationsMigration': {'status': 'unknown'}}, {'phase': 'infrastructure', 'status': 'unknown'}, {'phase': 'terraform-destroy', 'status': 'unknown'}):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                path.write_text(json.dumps(dict(state, operationsStore={'revision': 'r1'})))
                result = subprocess.run(['bash', '-c', 'source "$1"; require_remote_submission_settled "$2"', '_', str(SCRIPTS / 'lib.sh'), str(path)], capture_output=True)
                self.assertNotEqual(result.returncode, 0)

    def test_non_json_env_write_skips_assert_but_corrupt_json_fails(self):
        spec = importlib.util.spec_from_file_location('operations_hooks_test', SCRIPTS / 'operations_hooks.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory, patch('subprocess.run') as cloud:
            env = Path(directory) / 'candidate.env'
            env.write_text('KEY=value\n')
            module.assert_current(env)
            corrupt = Path(directory) / 'manifest.json'
            corrupt.write_text('{corrupt')
            with self.assertRaises(json.JSONDecodeError):
                module.assert_current(corrupt)
            cloud.assert_not_called()

    def test_unresolved_submission_blocks_retry_but_not_legacy(self):
        for bound in (True, False):
            with self.subTest(bound=bound), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                data = {'remoteSubmission': {'status': 'unknown'}}
                if bound:
                    data['operationsStore'] = {'revision': 'r1'}
                path.write_text(json.dumps(data))
                result = subprocess.run(['bash', '-c', 'source "$1"; require_remote_submission_settled "$2"', '_', str(SCRIPTS / 'lib.sh'), str(path)], capture_output=True)
                self.assertEqual(result.returncode == 0, not bound)

    def test_resolver_passes_explicit_cloud_selector(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / 'python3'
            fake.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            fake.chmod(0o700)
            result = subprocess.run(['bash', str(UPGRADE / 'resolve-deployment.sh'), '--search-root', directory, '--region', 'cn-hangzhou', '--deployment-id', 'deploy-123'], env=dict(os.environ, PATH=directory + os.pathsep + os.environ['PATH']), text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('operations-store.py', result.stdout)
            self.assertIn('resolve\n--project-root\n', result.stdout)
            self.assertIn('--region\ncn-hangzhou\n--deployment-id\ndeploy-123', result.stdout)

    def test_python_bound_write_propagates_checkpoint_failure(self):
        spec = importlib.util.spec_from_file_location('upgrade_info_hooks_test', UPGRADE / 'upgrade_info.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            with patch('subprocess.run', side_effect=subprocess.CalledProcessError(1, ['checkpoint'])):
                with self.assertRaises(Exception):
                    module.atomic_write_json(path, {'operationsStore': {'revision': 'old'}})

if __name__ == '__main__':
    unittest.main()
