"""Reject teardown preparation changes outside the explicitly owned protections."""
import copy
import importlib.util
import json
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/teardown_plan.py'


class TeardownPlanTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), 'teardown plan reviewer is missing')
        spec = importlib.util.spec_from_file_location('teardown_plan', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.manifest = {'deploymentId': 'aw-round-one', 'region': 'cn-hangzhou',
                         'accountUid': '1234567890123456', 'environment': 'auto-wonder-prod',
                         'publicSourceCidrs': ['203.0.113.1/32'],
                         'resources': {'ecs_instance_ids': {'zone_a': 'i-a', 'zone_b': 'i-b'},
                            'load_balancer_id': 'alb-one', 'rds': {'instance_id': 'rm-one'},
                            'redis': {'instance_id': 'r-one'}, 'package_bucket': 'packages-one',
                            'artifact_bucket': 'artifacts-one',
                            'sls': {'project': 'logs-one', 'stores': {'system': 'system', 'business': 'business', 'metrics': 'metrics'}}}}
        addresses = '''alicloud_vpc.main alicloud_vswitch.zone_a alicloud_vswitch.zone_b
alicloud_security_group.app alicloud_security_group_rule.vpc_internal alicloud_security_group_rule.alb_service
alicloud_alb_load_balancer.app alicloud_alb_server_group.app alicloud_alb_listener.application
alicloud_alb_acl.public_sources alicloud_alb_listener_acl_attachment.public_sources
alicloud_db_instance.main alicloud_db_backup_policy.main alicloud_rds_account.app alicloud_db_database.app alicloud_db_account_privilege.app
alicloud_kvstore_instance.main alicloud_oss_bucket.package alicloud_oss_bucket.artifact
alicloud_log_project.main alicloud_log_store.system alicloud_log_store.business alicloud_log_store.metrics
alicloud_log_store_index.system alicloud_log_store_index.business alicloud_ram_user.app alicloud_ram_policy.app
alicloud_ram_user_policy_attachment.app alicloud_ram_access_key.app'''.split()
        addresses += ['alicloud_instance.app["zone_a"]', 'alicloud_instance.app["zone_b"]',
                      'alicloud_alb_acl_entry_attachment.public_sources["203.0.113.1/32"]']
        tags = {'Project': 'AutoWonder', 'DeploymentId': 'aw-round-one', 'Environment': 'auto-wonder-prod',
                'ManagedBy': 'Terraform', 'Topology': 'multi-az-ha'}
        values = {address: {'id': 'fixture-' + str(i), 'tags': tags} for i, address in enumerate(addresses)}
        for address, instance in [('alicloud_instance.app["zone_a"]', 'i-a'), ('alicloud_instance.app["zone_b"]', 'i-b'),
                                  ('alicloud_alb_load_balancer.app', 'alb-one'), ('alicloud_db_instance.main', 'rm-one'),
                                  ('alicloud_kvstore_instance.main', 'r-one')]:
            values[address]['id'] = instance
        values['alicloud_db_backup_policy.main']['instance_id'] = 'rm-one'
        for kind in ('package', 'artifact'):
            values['alicloud_oss_bucket.' + kind].update(bucket=kind + 's-one', id=kind + 's-one')
        values['alicloud_log_project.main']['project_name'] = 'logs-one'
        for name in ('system', 'business', 'metrics'):
            values['alicloud_log_store.' + name].update(project_name='logs-one', logstore_name=name)
        self.plan = {'resource_changes': [{'address': address, 'mode': 'managed',
                       'change': {'actions': ['no-op'], 'before': value, 'after': copy.deepcopy(value), 'after_unknown': {}}}
                       for address, value in values.items()]}
        self.changes = {item['address']: item for item in self.plan['resource_changes']}
        self.update('alicloud_db_instance.main', 'deletion_protection', True, False)
        self.update('alicloud_kvstore_instance.main', 'instance_release_protection', True, False)
        for kind in ('package', 'artifact'):
            self.update('alicloud_oss_bucket.' + kind, 'force_destroy', False, True)
            self.update('alicloud_oss_bucket.' + kind, 'versioning', [{'status': 'Enabled'}], [{'status': 'Suspended'}])
        for field in ('backup_retention_period', 'log_backup_retention_period'):
            self.update('alicloud_db_backup_policy.main', field, 30, 7)
        self.update('alicloud_db_backup_policy.main', 'released_keep_policy', 'All', 'None')
        for name in ('system', 'business', 'metrics'):
            self.update('alicloud_log_store.' + name, 'retention_period', 30, 7)

    def update(self, address, field, before, after):
        change = self.changes[address]['change']
        change['actions'] = ['update']
        change['before'][field] = before
        change['after'][field] = after

    def reject(self):
        with self.assertRaises(self.module.ReviewError):
            self.module.review(self.manifest, self.plan)

    def test_allows_only_owned_protection_and_retention_updates(self):
        self.assertEqual(8, self.module.review(self.manifest, self.plan)['updatedResources'])

    def test_rejects_network_price_unknowns_and_replacements(self):
        original = copy.deepcopy(self.plan)
        for field, value in [('instance_charge_type', 'Postpaid'), ('security_ips', ['0.0.0.0/0']), ('instance_storage', 999)]:
            self.plan = copy.deepcopy(original)
            next(item for item in self.plan['resource_changes'] if item['address'] == 'alicloud_db_instance.main')['change']['after'][field] = value
            self.reject()
        self.plan = copy.deepcopy(original)
        self.plan['resource_changes'][0]['change']['actions'] = ['delete', 'create']
        self.reject()
        self.plan = copy.deepcopy(original)
        next(item for item in self.plan['resource_changes'] if item['address'] == 'alicloud_db_instance.main')['change']['after_unknown'] = {'deletion_protection': True}
        self.reject()

    def test_rejects_foreign_instance_tag_and_bucket(self):
        original = copy.deepcopy(self.plan)
        for address, field, value in [('alicloud_db_instance.main', 'id', 'rm-foreign'),
                                      ('alicloud_oss_bucket.package', 'bucket', 'another-bucket'),
                                      ('alicloud_log_store.system', 'project_name', 'foreign-project')]:
            self.plan = copy.deepcopy(original)
            item = next(item for item in self.plan['resource_changes'] if item['address'] == address)
            item['change']['before'][field] = value
            item['change']['after'][field] = value
            self.reject()
        self.plan = copy.deepcopy(original)
        self.plan['resource_changes'][0]['change']['before']['tags']['DeploymentId'] = 'foreign'
        self.reject()

    def test_missing_ownership_tags_is_not_accepted(self):
        for side in ('before', 'after'):
            del self.changes['alicloud_vpc.main']['change'][side]['tags']
        self.reject()

    def test_rejects_missing_extra_and_duplicate_resource_addresses(self):
        original = copy.deepcopy(self.plan)
        self.plan['resource_changes'] = [item for item in self.plan['resource_changes'] if item['address'] != 'alicloud_db_backup_policy.main']
        self.reject()
        self.plan = copy.deepcopy(original)
        self.plan['resource_changes'].append(copy.deepcopy(self.plan['resource_changes'][0]))
        self.reject()
        self.plan = copy.deepcopy(original)
        self.plan['resource_changes'][0]['address'] = 'alicloud_vpc.foreign'
        self.reject()

    def test_target_plan_with_known_dependencies_is_allowed(self):
        dependencies = {'alicloud_vpc.main', 'alicloud_vswitch.zone_a', 'alicloud_vswitch.zone_b', 'alicloud_log_project.main'}
        self.plan['resource_changes'] = [item for item in self.plan['resource_changes']
                                         if item['change']['actions'] == ['update'] or item['address'] in dependencies]
        result = self.module.review(self.manifest, self.plan)
        self.assertEqual(8, result['updatedResources'])
        self.assertEqual(12, result['resourceCount'])

    def test_alb_computed_set_update_remains_forbidden(self):
        change = self.changes['alicloud_alb_server_group.app']['change']
        change['actions'] = ['update']
        change['before']['servers'] = [{'server_id': 'i-a', 'port': 7001, 'server_type': 'Ecs', 'weight': 100, 'status': 'Available'}]
        change['after']['servers'] = [{'server_id': 'i-a', 'port': 7001, 'server_type': 'Ecs', 'weight': 100}]
        change['after_unknown'] = {'servers': [{'status': True}]}
        self.reject()

    def test_rejects_wrong_retention_direction_and_unchanged_protection(self):
        self.changes['alicloud_db_instance.main']['change']['after']['deletion_protection'] = True
        self.reject()
        self.changes['alicloud_db_instance.main']['change']['after']['deletion_protection'] = False
        self.changes['alicloud_log_store.system']['change']['after']['retention_period'] = 365
        self.reject()

    def actual_post_plan(self):
        plan = copy.deepcopy(self.plan)
        for item in plan['resource_changes']:
            item['change']['before'] = copy.deepcopy(item['change']['after'])
            item['change']['actions'] = ['no-op']
        backup = next(item for item in plan['resource_changes'] if item['address'] == 'alicloud_db_backup_policy.main')
        backup['change']['before']['backup_retention_period'] = 30
        backup['change']['actions'] = ['update']
        return plan

    def test_post_accepts_only_original_unchanged_backup_retention(self):
        self.assertTrue(hasattr(self.module, 'post_review'), 'post review is missing')
        result = self.module.post_review(self.manifest, self.actual_post_plan(), self.plan)
        self.assertEqual(30, result['retainedBackupRetentionDays'])
        self.assertEqual(1, result['remainingPlanUpdates'])

    def test_post_rejects_unreleased_protection_despite_planned_safe_value(self):
        self.assertTrue(hasattr(self.module, 'post_review'), 'post review is missing')
        post = self.actual_post_plan()
        change = next(item for item in post['resource_changes'] if item['address'] == 'alicloud_db_instance.main')['change']
        change['before']['deletion_protection'] = True
        change['actions'] = ['update']
        with self.assertRaises(self.module.ReviewError):
            self.module.post_review(self.manifest, post, self.plan)

    def test_post_rejects_unreviewed_retention_and_other_remaining_differences(self):
        self.assertTrue(hasattr(self.module, 'post_review'), 'post review is missing')
        for field, value in [('backup_retention_period', 90), ('log_backup_retention_period', 30), ('released_keep_policy', 'All')]:
            post = self.actual_post_plan()
            backup = next(item for item in post['resource_changes'] if item['address'] == 'alicloud_db_backup_policy.main')
            backup['change']['before'][field] = value
            with self.assertRaises(self.module.ReviewError):
                self.module.post_review(self.manifest, post, self.plan)
        post = self.actual_post_plan()
        backup = next(item for item in post['resource_changes'] if item['address'] == 'alicloud_db_backup_policy.main')
        backup['change']['after_unknown'] = {'backup_retention_period': True}
        with self.assertRaises(self.module.ReviewError):
            self.module.post_review(self.manifest, post, self.plan)

    def test_noop_provider_computed_attributes_do_not_expand_mutation_scope(self):
        self.changes['alicloud_instance.app["zone_a"]']['change']['after_unknown'] = {'computed_only': True}
        self.assertEqual(8, self.module.review(self.manifest, self.plan)['updatedResources'])


class TeardownPreparationScriptTests(unittest.TestCase):
    update = TeardownPlanTests.update

    def setUp(self):
        TeardownPlanTests.setUp(self)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scripts = self.root / 'scripts'
        self.scripts.mkdir()
        source = SCRIPT.parent / 'prepare-teardown.sh'
        self.assertTrue(source.exists(), 'teardown preparation entrypoint is missing')
        shutil.copy(source, self.scripts)
        shutil.copy(SCRIPT, self.scripts)
        (self.scripts / 'configure-terraform-acceleration.sh').write_text('echo /dev/null\n')
        (self.scripts / 'lib.sh').write_text('''set -euo pipefail
umask 077
TEMP_FILES=()
require_no_secret_args() { :; }
require_file() { test -f "$1"; }
require_command() { command -v "$1" >/dev/null; }
json_validate() { :; }
reject_secret_keys() { :; }
configure_cloud_profile() { :; }
require_remote_submission_settled() { :; }
require_mode_600() { :; }
json_string() { jq -r "$2" "$1"; }
ensure_alicloud_profile_identity() { AUTOWONDER_IDENTITY_JSON=$(printf '{"AccountId":"%s"}' "${TEST_ACCOUNT:-1234567890123456}"); }
die() { echo "$*" >&2; exit 1; }
sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
atomic_jq() { local path=$1; shift; jq "$@" "$path" > "$path.next"; mv "$path.next" "$path"; }
''')
        self.work = self.root / 'terraform'
        self.work.mkdir()
        digest = hashlib.sha256(b'1234567890123456|cn-hangzhou|aw-round-one').hexdigest()[:12]
        bucket = 'aw-tfstate-aw-round-one-' + digest
        backend = self.work / 'backend.hcl'
        backend.write_text('bucket = "' + bucket + '"\nkey = "states/aw-round-one/terraform.tfstate"\nregion = "cn-hangzhou"\nendpoint = "oss-cn-hangzhou.aliyuncs.com"\n')
        (self.work / 'terraform-secrets.env').write_text('TF_VAR_test=fixture\n')
        self.manifest.update(stateMode='remote', localContext={'terraformDirectory': str(self.work)},
                             terraform={'backendDirectory': str(self.work), 'stateReference': str(backend),
                                        'stateBucket': bucket, 'stateKey': 'states/aw-round-one/terraform.tfstate', 'backendStatus': 'ready'})
        self.manifest_path = self.root / 'manifest.json'
        self.manifest_path.write_text(json.dumps(self.manifest))
        self.confirmation = self.root / 'confirm'
        self.confirmation.write_text('DESTROY aw-round-one\n')
        self.fixture = self.root / 'plan.json'
        self.fixture.write_text(json.dumps(self.plan))
        binary = self.root / 'bin'
        binary.mkdir()
        terraform = binary / 'terraform'
        terraform.write_text('#!' + sys.executable + '\n' + '''import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as stream: stream.write(json.dumps(args) + '\\n')
if 'plan' in args:
    path = next(arg.split('=', 1)[1] for arg in args if arg.startswith('-out='))
    pathlib.Path(path).write_text('saved-plan')
elif 'show' in args:
    plan = json.loads(pathlib.Path(os.environ['PLAN_FIXTURE']).read_text())
    if 'post' in args[-1]:
        for item in plan['resource_changes']:
            item['change']['actions'] = ['no-op']
            item['change']['before'] = json.loads(json.dumps(item['change']['after']))
            if os.environ.get('RETAIN_BACKUP_DAYS') and item['address'] == 'alicloud_db_backup_policy.main':
                item['change']['before']['backup_retention_period'] = int(os.environ['RETAIN_BACKUP_DAYS'])
                item['change']['actions'] = ['update']
    print(json.dumps(plan))
elif 'apply' in args:
    print('fixture-secret-must-stay-private')
    if os.environ.get('FAIL_APPLY'): sys.exit(7)
''')
        terraform.chmod(0o700)
        self.env = dict(os.environ, PATH=str(binary) + ':' + os.environ['PATH'],
                        COMMAND_LOG=str(self.root / 'commands'), PLAN_FIXTURE=str(self.fixture))

    def run_script(self, reconcile=False, **extra):
        return subprocess.run(['bash', str(self.scripts / 'prepare-teardown.sh'), '--manifest', str(self.manifest_path),
                               '--confirmation-file', str(self.confirmation)] + (['--reconcile'] if reconcile else []), env=dict(self.env, **extra), capture_output=True, text=True)

    def test_script_applies_only_reviewed_plan_and_records_completion(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('fixture-secret', result.stdout + result.stderr)
        self.assertEqual('complete', json.loads(self.manifest_path.read_text())['teardownPreparation']['status'])
        commands = (self.root / 'commands').read_text()
        self.assertIn('-var=lifecycle_mode=temporary', commands)
        self.assertNotIn('-auto-approve', commands)
        plans = [json.loads(line) for line in commands.splitlines() if 'plan' in json.loads(line)]
        self.assertEqual(2, len(plans))
        expected = {
            '-target=alicloud_db_instance.main', '-target=alicloud_db_backup_policy.main',
            '-target=alicloud_kvstore_instance.main', '-target=alicloud_oss_bucket.package',
            '-target=alicloud_oss_bucket.artifact', '-target=alicloud_log_store.system',
            '-target=alicloud_log_store.business', '-target=alicloud_log_store.metrics',
        }
        for plan in plans:
            self.assertEqual(expected, {argument for argument in plan if argument.startswith('-target=')})

    def test_script_unknown_apply_retains_pending_and_blocks_retry(self):
        first = self.run_script(FAIL_APPLY='1')
        self.assertNotEqual(0, first.returncode)
        self.assertEqual('pending', json.loads(self.manifest_path.read_text())['teardownPreparation']['status'])
        original = (self.root / 'commands').read_bytes()
        second = self.run_script()
        self.assertNotEqual(0, second.returncode)
        self.assertEqual(original, (self.root / 'commands').read_bytes())

    def test_script_records_retention_exception_without_reapplying(self):
        result = self.run_script(RETAIN_BACKUP_DAYS='30')
        self.assertEqual(0, result.returncode, result.stderr)
        preparation = json.loads(self.manifest_path.read_text())['teardownPreparation']
        self.assertEqual('complete', preparation['status'])
        self.assertEqual(30, preparation['postcheck']['retainedBackupRetentionDays'])
        self.assertRegex(preparation['postPlanFingerprint'], r'^[0-9a-f]{64}$')

    def test_pending_reconciliation_only_plans_and_never_reapplies(self):
        self.assertNotEqual(0, self.run_script(FAIL_APPLY='1').returncode)
        previous = (self.root / 'commands').read_text().splitlines()
        result = self.run_script(reconcile=True, RETAIN_BACKUP_DAYS='30')
        self.assertEqual(0, result.returncode, result.stderr)
        commands = [json.loads(line) for line in (self.root / 'commands').read_text().splitlines()[len(previous):]]
        self.assertFalse(any('apply' in command for command in commands))
        self.assertEqual(1, sum('plan' in command for command in commands))
        self.assertEqual('complete', json.loads(self.manifest_path.read_text())['teardownPreparation']['status'])

    def test_reconciliation_hash_mismatch_does_not_start_terraform(self):
        self.assertNotEqual(0, self.run_script(FAIL_APPLY='1').returncode)
        (self.work / 'teardown-preparation.tfplan').write_text('changed-plan')
        previous = (self.root / 'commands').read_bytes()
        result = self.run_script(reconcile=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(previous, (self.root / 'commands').read_bytes())
        self.assertEqual('pending', json.loads(self.manifest_path.read_text())['teardownPreparation']['status'])

    def test_reconciliation_rechecks_original_plan_scope(self):
        self.assertNotEqual(0, self.run_script(FAIL_APPLY='1').returncode)
        self.changes['alicloud_db_instance.main']['change']['after']['instance_charge_type'] = 'Postpaid'
        self.fixture.write_text(json.dumps(self.plan))
        previous = (self.root / 'commands').read_text().splitlines()
        result = self.run_script(reconcile=True)
        self.assertNotEqual(0, result.returncode)
        commands = [json.loads(line) for line in (self.root / 'commands').read_text().splitlines()[len(previous):]]
        self.assertFalse(any('apply' in command or 'plan' in command for command in commands))
        self.assertEqual('pending', json.loads(self.manifest_path.read_text())['teardownPreparation']['status'])

    def test_backend_metadata_tampering_blocks_before_terraform(self):
        backend = self.work / 'backend.hcl'
        backend.write_text(backend.read_text().replace('states/aw-round-one/', 'states/another-deployment/'))
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'commands').exists())

    def test_machine_review_rejection_does_not_apply_or_mark_pending(self):
        self.changes['alicloud_db_instance.main']['change']['after']['instance_charge_type'] = 'Postpaid'
        self.fixture.write_text(json.dumps(self.plan))
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        commands = [json.loads(line) for line in (self.root / 'commands').read_text().splitlines()]
        self.assertFalse(any('apply' in command for command in commands))
        self.assertNotIn('teardownPreparation', json.loads(self.manifest_path.read_text()))

    def test_script_confirmation_and_identity_block_before_terraform(self):
        result = self.run_script(TEST_ACCOUNT='9999999999999999')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'commands').exists())
        self.confirmation.write_text('DESTROY another-deployment\n')
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'commands').exists())


if __name__ == '__main__':
    unittest.main()
