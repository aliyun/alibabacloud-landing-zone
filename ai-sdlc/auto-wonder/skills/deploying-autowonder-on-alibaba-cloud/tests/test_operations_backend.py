import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))


class BackendTests(unittest.TestCase):
    def setUp(self):
        script = SCRIPTS / 'operations_backend.py'
        self.assertTrue(script.exists(), 'Migration implementation is missing')
        spec = importlib.util.spec_from_file_location('operations_backend', script)
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.tf = self.root / 'terraform'
        self.tf.mkdir()
        (self.tf / 'main.tf').write_text('resource "alicloud_instance" "app" {}')
        self.state = {'version': 4, 'lineage': 'lineage-1', 'serial': 7, 'resources': [{'mode': 'managed', 'type': 'alicloud_instance', 'name': 'app', 'provider': 'provider["registry.terraform.io/aliyun/alicloud"]', 'instances': [{'schema_version': 1, 'attributes': {'id': 'i-original'}}]}]}
        self.state_bytes = json.dumps(self.state).encode()
        (self.tf / 'terraform.tfstate').write_bytes(self.state_bytes)
        self.manifest = self.root / 'deployment.json'
        self.data = {'deploymentId': 'demo', 'region': 'cn-hangzhou', 'stateMode': 'local', 'localContext': {'terraformDirectory': str(self.tf)}, 'operationsStore': {'bucket': 'aw-ops-demo-123', 'accountUid': '12345', 'region': 'cn-hangzhou', 'deploymentId': 'demo'}}
        self.manifest.write_text(json.dumps(self.data))
        self.config = self.root / 'cli.json'
        self.config.write_text(json.dumps({'profiles': [{'name': 'auto-wonder', 'access_key_id': 'secret-key', 'access_key_secret': 'secret-secret', 'sts_token': 'secret-token'}]}))
        env = patch.dict(os.environ, {'ALIBABA_CLOUD_CLI_CONFIG_FILE': str(self.config), 'TF_WORKSPACE': 'default'})
        env.start()
        self.addCleanup(env.stop)
        self.store = Mock()
        self.store.identity.return_value = '12345'
        self.store.get.return_value = None
        self.output = self.state
        self.fail = False
        self.calls = []
        runner = patch.object(self.mod.subprocess, 'run', side_effect=self.run_command)
        runner.start()
        self.addCleanup(runner.stop)

    def run_command(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if self.fail:
            return subprocess.CompletedProcess(args, 1, b'secret-secret', b'secret-token')
        return subprocess.CompletedProcess(args, 0, json.dumps(self.output).encode() if 'pull' in args else b'', b'')

    def test_migration_verifies_state_and_keeps_backup_and_private_credentials(self):
        self.mod.migrate_local_state(self.manifest, self.store)
        result = json.loads(self.manifest.read_text())
        self.assertEqual(result['stateMode'], 'remote')
        self.assertEqual(result['terraform']['backendStatus'], 'ready')
        self.assertEqual(result['terraform']['stateBucket'], 'aw-ops-demo-123')
        self.assertEqual(result['terraform']['stateKey'], 'deploy/terraform-state/demo/terraform.tfstate')
        self.assertIn('-migrate-state', self.calls[0][0])
        self.assertIn('-force-copy', self.calls[0][0])
        self.assertIn('-input=false', self.calls[0][0])
        for args, kw in self.calls:
            self.assertNotIn('secret-secret', repr(args))
            self.assertEqual(kw['env']['ALICLOUD_SECRET_KEY'], 'secret-secret')
        self.assertEqual((self.tf / 'terraform.tfstate.pre-operations-migration.backup').read_bytes(), self.state_bytes)
        backend = Path(result['terraform']['stateReference']).read_text()
        self.assertNotIn('secret-', backend)
        self.assertIn('encrypt = true', backend)
        self.assertTrue((self.tf / 'backend.tf').exists())

    def test_existing_destination_state_is_never_force_overwritten(self):
        self.store.get.return_value = self.state_bytes
        with self.assertRaises(self.mod.BackendError):
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertFalse(self.calls)

    def test_resource_replacement_is_rejected(self):
        self.output = json.loads(self.state_bytes)
        self.output['resources'][0]['instances'][0]['attributes']['id'] = 'i-replacement'
        with self.assertRaises(self.mod.BackendError):
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertEqual(json.loads(self.manifest.read_text())['stateMode'], 'local')

    def test_cloud_success_manifest_write_failure_keeps_unknown_marker(self):
        original_write = self.mod.private_write
        def write(path, content):
            if Path(path) == self.manifest:
                raise OSError('disk full')
            return original_write(path, content)
        with patch.object(self.mod, 'private_write', side_effect=write):
            with self.assertRaises(self.mod.BackendError):
                self.mod.migrate_local_state(self.manifest, self.store)
        self.assertTrue((self.tf / '.operations-state-migration.json').exists())
        self.assertEqual(json.loads(self.manifest.read_text())['stateMode'], 'local')

    def test_stage_init_reuses_migrated_backend_and_accepts_saved_coordinates(self):
        self.mod.migrate_local_state(self.manifest, self.store)
        stage = (SCRIPTS / 'terraform-stage.sh').read_text()
        initializer = stage.split('terraform_init() {', 1)[1].split('\ncase "$command" in', 1)[0]
        script = ('work_dir="$1"; manifest="$2"\n'
                  'json_string() { jq -er "$2" "$1"; }\n'
                  'require_file() { test -f "$1"; }\n'
                  'die() { exit 1; }\n'
                  'terraform() { :; }\n'
                  'terraform_init() {' + initializer + '\nterraform_init\n')
        # Run the real stage initializer against migrated files; only its external
        # Terraform invocation is replaced, so no provider or cloud is contacted.
        process = subprocess.Popen(['bash', '-e', '-c', script, 'test', str(self.tf), str(self.manifest)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(process.returncode, 0, stderr.decode())
        declarations = sum(len(re.findall(r'backend\s+"oss"', source.read_text())) for source in self.tf.glob('*.tf'))
        self.assertEqual(declarations, 1, 'Migration followed by stage must not create duplicate backends')
        data = json.loads(self.manifest.read_text())['terraform']
        self.assertEqual(data['stateReference'], str(Path(data['backendDirectory']) / 'backend.hcl'))

    def test_existing_remote_is_noop(self):
        self.data['stateMode'] = 'remote'
        self.manifest.write_text(json.dumps(self.data))
        before = self.manifest.read_bytes()
        self.mod.migrate_local_state(self.manifest, self.store)
        self.assertEqual(before, self.manifest.read_bytes())
        self.assertFalse(self.calls)
        self.store.identity.assert_not_called()

    def test_lost_resource_rejects_ready_and_blocks_retry(self):
        self.output = dict(self.state, resources=[])
        with self.assertRaises(self.mod.BackendError):
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertEqual(json.loads(self.manifest.read_text())['stateMode'], 'local')
        self.assertTrue((self.tf / '.operations-state-migration.json').exists())
        before = len(self.calls)
        with self.assertRaises(self.mod.BackendError):
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertEqual(before, len(self.calls))

    def test_lineage_and_serial_change_rejected(self):
        for field, value in (('lineage', 'other'), ('serial', 6)):
            with self.subTest(field=field):
                self.output = dict(self.state, **{field: value})
                with self.assertRaises(self.mod.BackendError):
                    self.mod.migrate_local_state(self.manifest, self.store)
                for name in ('.operations-state-migration.json', 'backend.tf', 'backend.hcl', 'terraform.tfstate.pre-operations-migration.backup'):
                    (self.tf / name).unlink(missing_ok=True)

    def test_failure_sanitized_and_not_ready(self):
        self.fail = True
        with self.assertRaises(self.mod.BackendError) as caught:
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertNotIn('secret-', str(caught.exception))
        self.assertNotEqual(json.loads(self.manifest.read_text()).get('terraform', {}).get('backendStatus'), 'ready')

    def test_other_workspace_rejected_before_migration(self):
        (self.tf / 'terraform.tfstate.d' / 'prod').mkdir(parents=True)
        with self.assertRaises(self.mod.BackendError):
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertFalse(self.calls)

    def test_existing_backend_definition_rejected(self):
        (self.tf / 'backend.tf').write_text('terraform { backend "local" {} }')
        with self.assertRaises(self.mod.BackendError):
            self.mod.migrate_local_state(self.manifest, self.store)
        self.assertFalse(self.calls)


if __name__ == '__main__':
    unittest.main()
