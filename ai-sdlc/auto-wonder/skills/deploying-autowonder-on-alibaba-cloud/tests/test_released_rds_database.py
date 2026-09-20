"""Offline repair of the one database state orphan left by a confirmed RDS refund."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
SCRIPT = SCRIPTS / 'reconcile_released_rds_database.py'


class ReleasedDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), 'released RDS database reconciliation is missing')
        sys.path.insert(0, str(SCRIPTS))
        self.addCleanup(lambda: sys.path.remove(str(SCRIPTS)))
        spec = importlib.util.spec_from_file_location('rds_reconcile_test', SCRIPT)
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / 'terraform'
        self.work.mkdir()
        bucket = 'aw-tfstate-aw-fixture-' + hashlib.sha256(b'1234567890123456|cn-hangzhou|aw-fixture').hexdigest()[:12]
        backend = self.work / 'backend.hcl'
        backend.write_text('bucket = "' + bucket + '"\nkey = "states/aw-fixture/terraform.tfstate"\nregion = "cn-hangzhou"\nendpoint = "oss-cn-hangzhou.aliyuncs.com"\n')
        self.manifest = self.root / 'manifest.json'
        self.data = {'accountUid': '1234567890123456', 'region': 'cn-hangzhou', 'deploymentId': 'aw-fixture',
                     'stateMode': 'remote', 'localContext': {'terraformDirectory': str(self.work)},
                     'terraform': {'backendDirectory': str(self.work), 'stateReference': str(backend), 'backendStatus': 'ready',
                                   'stateBucket': bucket, 'stateKey': 'states/aw-fixture/terraform.tfstate'},
                     'teardownPreparation': {'status': 'complete'},
                     'resources': {'rds': {'instance_id': 'rm-fixture', 'database': 'autowonder'}}}
        self.manifest.write_text(json.dumps(self.data))
        self.confirmation = self.root / 'confirm'
        self.confirmation.write_text('DESTROY aw-fixture\n')
        self.original = {'version': 4, 'terraform_version': '1.14.0', 'serial': 10, 'lineage': 'fixture-lineage',
                         'outputs': {'protected': {'sensitive': True, 'value': 'never-print'}},
                         'resources': [
                             {'mode': 'managed', 'type': 'alicloud_db_database', 'name': 'app', 'provider': 'provider["registry.terraform.io/aliyun/alicloud"]',
                              'instances': [{'schema_version': 0, 'attributes': {'id': 'rm-fixture:autowonder', 'instance_id': 'rm-fixture', 'data_base_name': 'autowonder'}}]},
                             {'mode': 'managed', 'type': 'alicloud_oss_bucket', 'name': 'artifact', 'instances': [{'attributes': {'id': 'owned-bucket'}}]}]}
        self.state = copy.deepcopy(self.original)
        self.commands = []
        self.pull_count = 0
        self.check_results = None
        self.error = b'ERROR: SDK.ServerError\nErrorCode: InvalidDBInstanceName.NotFound\nMessage: instance missing\nRequestId: fixture\n'
        self.identity_account = '1234567890123456'
        self.rds_exists = False
        self.remove_error = False
        self.remove_applied = True
        self.mutate_other = False
        self.runner = mock.patch.object(self.mod.subprocess, 'run', side_effect=self.run_command)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def run_command(self, args, **kwargs):
        self.commands.append(args)
        if 'GetCallerIdentity' in args:
            return subprocess.CompletedProcess(args, 0, json.dumps({'AccountId': self.identity_account}).encode(), b'')
        if 'DescribeDBInstanceAttribute' in args:
            self.assertIn('--profile', args)
            self.assertEqual('auto-wonder', args[args.index('--profile') + 1])
            self.assertEqual('cn-hangzhou', args[args.index('--region') + 1])
            self.assertEqual('rm-fixture', args[args.index('--DBInstanceId') + 1])
            return subprocess.CompletedProcess(args, 0 if self.rds_exists else 1, b'{}' if self.rds_exists else b'', self.error)
        if 'init' in args:
            return subprocess.CompletedProcess(args, 0, b'', b'')
        if 'pull' in args:
            self.pull_count += 1
            snapshot = copy.deepcopy(self.state)
            if self.check_results is not None:
                shift = self.pull_count % len(self.check_results)
                snapshot['check_results'] = self.check_results[shift:] + self.check_results[:shift]
            return subprocess.CompletedProcess(args, 0, json.dumps(snapshot).encode(), b'')
        if 'rm' in args:
            self.assertEqual('alicloud_db_database.app', args[-1])
            self.assertIn('-lock=true', args)
            if '-dry-run' in args:
                return subprocess.CompletedProcess(args, 0, b'Would remove alicloud_db_database.app\n', b'')
            if self.remove_applied:
                self.state['resources'] = self.state['resources'][1:]
                self.state['serial'] += 1
            if self.mutate_other:
                self.state['resources'][0]['instances'][0]['attributes']['id'] = 'foreign-bucket'
            return subprocess.CompletedProcess(args, 1 if self.remove_error else 0, b'', b'unknown transport result' if self.remove_error else b'')
        raise AssertionError(args)

    def execute(self):
        return self.mod.reconcile(self.manifest, self.confirmation)

    def test_only_exact_owned_orphan_is_removed_and_backup_stays_private(self):
        result = self.execute()
        self.assertEqual('complete', result['status'])
        data = json.loads(self.manifest.read_text())
        record = data['releasedRdsDatabaseStateRepair']
        backup = Path(record['stateFile'])
        self.assertEqual(0o600, backup.stat().st_mode & 0o777)
        self.assertEqual(self.original, json.loads(backup.read_text()))
        self.assertEqual(self.original['resources'][1:], self.state['resources'])
        self.assertNotIn('never-print', json.dumps(result))
        self.assertEqual(1, sum('rm' in command and '-dry-run' not in command for command in self.commands))

    def test_existing_uncertain_and_lookalike_errors_never_remove_state(self):
        for error in (b'timeout InvalidDBInstanceName.NotFound', b'ERROR: SDK.ServerError\nErrorCode: AccessDenied\nMessage: InvalidDBInstanceName.NotFound',
                      b'ERROR: SDK.ServerError\nErrorCode: InvalidDBInstanceName.NotFoundExtra',
                      b'ERROR: SDK.ServerError\nErrorCode: InvalidDBInstanceName.NotFound\nErrorCode: AccessDenied'):
            self.error = error
            with self.assertRaises(self.mod.ReconcileError):
                self.execute()
        self.rds_exists = True
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertFalse(any('rm' in command for command in self.commands))

    def test_current_cli_json_not_found_completes_reconciliation(self):
        self.error = json.dumps({'message': 'The specified DB instance name does not exist.',
                                 'error_code': 'InvalidDBInstanceName.NotFound', 'status_code': 400}).encode()
        result = self.execute()
        self.assertEqual('complete', result['status'])
        self.assertEqual(self.original['resources'][1:], self.state['resources'])

    def test_json_lookalike_errors_never_remove_state(self):
        for error in (json.dumps({'error_code': 'AccessDenied', 'status_code': 400}).encode(),
                      json.dumps({'error_code': 'InvalidDBInstanceName.NotFound', 'status_code': 403}).encode(),
                      json.dumps({'error_code': 'InvalidDBInstanceName.NotFound'}).encode(),
                      b'{"error_code": "InvalidDBInstanceName.NotFound", "status_code": 400} trailing'):
            self.error = error
            with self.assertRaises(self.mod.ReconcileError):
                self.execute()
        self.assertFalse(any('rm' in command for command in self.commands))

    def test_check_results_reordering_between_pulls_is_not_a_change(self):
        # Terraform reconstructs check_results in a different order on each read even
        # when nothing changed; the exact same deployment must still reconcile.
        self.check_results = [{'object_kind': 'var', 'config_addr': 'var.region', 'status': 'unknown', 'objects': None},
                              {'object_kind': 'var', 'config_addr': 'var.environment', 'status': 'unknown', 'objects': None}]
        result = self.execute()
        self.assertEqual('complete', result['status'])
        self.assertEqual(self.original['resources'][1:], self.state['resources'])
        self.assertEqual(1, sum('rm' in command and '-dry-run' not in command for command in self.commands))

    def test_wrong_database_identity_and_incomplete_preparation_are_rejected(self):
        for key, wrong in [('instance_id', 'rm-other'), ('data_base_name', 'other'), ('id', 'rm-other:autowonder')]:
            self.state = copy.deepcopy(self.original)
            self.state['resources'][0]['instances'][0]['attributes'][key] = wrong
            with self.assertRaises(self.mod.ReconcileError):
                self.execute()
        self.data['teardownPreparation']['status'] = 'pending'
        self.manifest.write_text(json.dumps(self.data))
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertFalse(any('rm' in command for command in self.commands))

    def test_pending_applied_unknown_reconciles_without_another_remove(self):
        self.remove_error = True
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertEqual('pending', json.loads(self.manifest.read_text())['releasedRdsDatabaseStateRepair']['status'])
        previous = len(self.commands)
        result = self.execute()
        self.assertEqual('complete', result['status'])
        self.assertFalse(any('rm' in command for command in self.commands[previous:]))

    def test_pending_not_applied_or_other_resource_changed_stays_pending(self):
        self.remove_applied = False
        self.remove_error = True
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        previous = len(self.commands)
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertFalse(any('rm' in command for command in self.commands[previous:]))
        self.assertEqual('pending', json.loads(self.manifest.read_text())['releasedRdsDatabaseStateRepair']['status'])

    def test_other_resource_change_prevents_completion(self):
        self.mutate_other = True
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertEqual('pending', json.loads(self.manifest.read_text())['releasedRdsDatabaseStateRepair']['status'])

    def test_wrong_sts_account_and_backend_binding_are_rejected(self):
        self.identity_account = '9999999999999999'
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertFalse(any('rm' in command or 'DescribeDBInstanceAttribute' in command for command in self.commands))
        self.identity_account = '1234567890123456'
        backend = self.work / 'backend.hcl'
        backend.write_text(backend.read_text().replace('states/aw-fixture/', 'states/another-deployment/'))
        from teardown_plan import ReviewError
        with self.assertRaises(ReviewError):
            self.execute()
        self.assertFalse(any('rm' in command for command in self.commands))

    def test_pending_backup_tampering_is_rejected_without_removal(self):
        self.remove_error = True
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        data = json.loads(self.manifest.read_text())
        Path(data['releasedRdsDatabaseStateRepair']['stateFile']).write_bytes(b'{}')
        previous = len(self.commands)
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertFalse(any('rm' in command for command in self.commands[previous:]))
        self.assertEqual('pending', json.loads(self.manifest.read_text())['releasedRdsDatabaseStateRepair']['status'])

    def test_confirmation_for_another_deployment_is_rejected_before_cloud(self):
        self.confirmation.write_text('DESTROY different-deployment\n')
        with self.assertRaises(self.mod.ReconcileError):
            self.execute()
        self.assertEqual([], self.commands)

    def test_absent_without_record_is_readonly_not_required(self):
        self.state['resources'] = self.state['resources'][1:]
        original_manifest = self.manifest.read_bytes()
        self.assertEqual('not-required', self.execute()['status'])
        self.assertEqual(original_manifest, self.manifest.read_bytes())
        self.assertFalse(any('rm' in command for command in self.commands))


if __name__ == '__main__':
    unittest.main()
