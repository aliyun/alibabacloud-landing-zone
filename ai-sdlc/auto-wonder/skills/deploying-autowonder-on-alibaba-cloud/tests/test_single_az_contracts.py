"""Single-zone business placement keeps the shared dual-zone ALB lifecycle."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import test_plan_binding
import test_resolve_zones
import test_script_contracts
import test_teardown_plan

rz = test_plan_binding.rz
ROOT = Path(__file__).resolve().parents[1]


class SingleAzContracts(unittest.TestCase):
    def test_single_zone_redis_quote_omits_secondary_zone(self):
        import resource_inventory as inventory
        queries = []

        def query(region, service, action, params):
            if service == 'r-kvstore' and action == 'DescribePrice':
                self.assertIsNone(params.get('SecondaryZoneId'),
                                  'Single-zone Redis API rejects an explicit same-zone SecondaryZoneId')
                queries.append(params)
            return test_resolve_zones.cloud_response_adapter(region, service, action, params)

        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            data = test_resolve_zones.ResolverIntegration().manifest(manifest)
            data['topology'] = 'single-az'
            manifest.write_text(json.dumps(data))
            with patch.object(inventory, 'request', side_effect=query):
                self.assertEqual(0, rz.resolve(manifest))
            self.assertTrue(queries)
            # Exercise the real CLI argument boundary: None must become no flag.
            with patch.object(inventory.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '{}', '')) as run:
                inventory.request('cn-beijing', 'r-kvstore', 'DescribePrice', queries[0])
                self.assertNotIn('--SecondaryZoneId', run.call_args.args[0])

    def test_backend_bucket_tags_match_single_zone_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'schemaVersion': 1, 'region': 'cn-beijing',
                'environment': 'auto-wonder-prod', 'deploymentId': 'prod-abc12345',
                'accountUid': '1234567890123456', 'topology': 'single-az',
                'stateMode': 'remote', 'terraform': {'backendStatus': 'pending'}}))
            binary = root / 'bin'
            binary.mkdir()
            profile = test_script_contracts.ScriptContracts.write_cloud_profile(root)
            aliyun = binary / 'aliyun'
            aliyun.write_text('#!/usr/bin/env bash\necho \'{"AccountId":"1234567890123456"}\'\n')
            aliyun.chmod(0o755)
            log = root / 'ossutil.log'
            ossutil = binary / 'ossutil'
            ossutil.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$OSSUTIL_LOG"
case "${1:-}:${2:-}" in
  version:*|--version:*) echo 'ossutil version 2.1.0';;
  help:cp|help:rm) echo '--endpoint --region --force';;
  help:presign) echo '--expires-duration --endpoint --region';;
  stat:*|api:put-bucket-acl|api:put-bucket-versioning|api:put-bucket-tags) exit 0;;
  *) exit 1;;
esac
''')
            ossutil.chmod(0o755)
            scripts = root / 'scripts'
            scripts.mkdir()
            for name in ('terraform-backend.sh', 'lib.sh', 'cloud_diagnostics.py'):
                (scripts / name).write_bytes((ROOT / 'scripts' / name).read_bytes())
            (scripts / 'operations-store.py').write_text('pass\n')
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                       OSSUTIL_LOG=str(log), ALIBABA_CLOUD_CLI_CONFIG_FILE=str(profile))
            run = subprocess.run(['bash', str(scripts / 'terraform-backend.sh'), 'prepare',
                '--manifest', str(manifest), '--project-root', str(root)], env=env, text=True, capture_output=True)
            self.assertEqual(0, run.returncode, run.stderr)
            tags = next(line for line in log.read_text().splitlines() if line.startswith('api put-bucket-tags '))
            self.assertIn('"Key":"Topology","Value":"single-az"', tags)

    def fixture(self):
        manifest, _ = test_plan_binding.PlanBindingTests().fixture()
        manifest['topology'] = 'single-az'
        zones = manifest['availabilityZones']
        placement = manifest['resolvedInfrastructure']['zonePlan']
        placement['ecsZones'] = zones[:1]
        placement['rds']['slaveZone'] = zones[0]
        placement['redis']['secondaryZone'] = zones[0]
        selected = rz.candidate_from_manifest(manifest)
        policy = rz.effective_policy(manifest)
        manifest['resourceSelection'] = {'status': 'verified', 'policy': policy, 'selected': selected,
            'selectionSha256': rz.digest({'policy': policy, 'selected': selected, 'existing': {}})}
        plan = {'variables': {k: {'value': v} for k, v in rz.expected_tfvars(manifest).items()},
                'resource_changes': []}
        for kind, values in rz.expected_resources(manifest).items():
            for index, value in enumerate(values):
                if kind == 'alicloud_alb_load_balancer':
                    value['zone_mappings'] = [{'zone_id': zone} for zone in zones]
                plan['resource_changes'].append({'address': kind + '.fixture' + str(index),
                    'type': kind, 'mode': 'managed',
                    'change': {'actions': ['create'], 'before': None, 'after': value}})
        next(row for row in plan['resource_changes'] if row['type'] == 'alicloud_instance')['address'] = 'alicloud_instance.app["zone_a"]'
        return manifest, plan

    def test_single_zone_implicit_node_name_is_bound_in_saved_plan(self):
        manifest, plan = self.fixture()
        self.assertNotIn('targetEcsNodes', manifest)
        next(row for row in plan['resource_changes'] if row['type'] == 'alicloud_instance')['address'] = 'alicloud_instance.app["zone_b"]'
        with self.assertRaises(rz.InventoryError):
            rz.validate_plan(manifest, plan)

    def test_effective_policy_supports_international_regions(self):
        for region in ('ap-southeast-1', 'eu-central-1', 'us-east-1'):
            with self.subTest(region=region):
                policy = rz.effective_policy({'region': region, 'topology': 'single-az'})
                self.assertEqual('public-cloud', policy['regionScope'])

    def test_effective_policy_rejects_malformed_region_identifiers(self):
        for region in ('', 'ap-southeast-1;echo injected', '../cn-beijing',
                       'https://region.example', 'US-EAST-1', 'us east 1', 'us-east-1\n'):
            with self.subTest(region=region), self.assertRaises(rz.InventoryError):
                rz.effective_policy({'region': region, 'topology': 'single-az'})

    def test_legacy_saved_policy_regions_do_not_block_international_region(self):
        policy = json.loads(rz.POLICY_FILE.read_text())
        policy.pop('regionScope', None)
        policy['regions'] = ['cn-zhangjiakou', 'cn-hangzhou', 'cn-shanghai', 'cn-beijing']
        effective = rz.effective_policy({'region': 'ap-southeast-1', 'topology': 'single-az',
                                        'resourceSelection': {'policy': policy}})
        self.assertEqual(policy['rds'], effective['rds'])
        self.assertEqual(policy['redis'], effective['redis'])

    def test_saved_plan_accepts_single_business_zone_with_dual_zone_alb(self):
        manifest, plan = self.fixture()
        rz.validate_plan(manifest, plan)
        self.assertEqual({'zone_a': manifest['availabilityZones'][0]}, rz.intended_nodes(manifest))
        self.assertEqual(2, len(manifest['availabilityZones']))
        self.assertEqual('single-az', plan['variables']['topology']['value'])
        self.assertEqual(1, sum(row['type'] == 'alicloud_instance' for row in plan['resource_changes']))

    def test_saved_plan_rejects_extra_node_and_cross_zone_database_or_cache(self):
        manifest, original = self.fixture()
        for kind, field in [('alicloud_instance', None), ('alicloud_db_instance', 'zone_id_slave_a'),
                            ('alicloud_kvstore_instance', 'secondary_zone_id')]:
            plan = copy.deepcopy(original)
            row = next(row for row in plan['resource_changes'] if row['type'] == kind)
            if field:
                row['change']['after'][field] = manifest['availabilityZones'][1]
            else:
                extra = copy.deepcopy(row)
                extra['address'] += '_extra'
                plan['resource_changes'].append(extra)
            with self.subTest(kind=kind), self.assertRaises(rz.InventoryError):
                rz.validate_plan(manifest, plan)

    def test_topology_field_change_cannot_reuse_accepted_selection(self):
        manifest, plan = self.fixture()
        manifest['resources']['ecs_instance_ids'] = {'zone_a': 'i-existing'}
        manifest['resources']['rds'] = {'instance_id': 'rm-existing'}
        manifest['resources']['redis'] = {'instance_id': 'r-existing'}
        manifest['resourceSelection']['selectionSha256'] = rz.digest({
            'policy': rz.effective_policy(manifest), 'selected': rz.candidate_from_manifest(manifest),
            'existing': rz.existing_constraints(manifest)})
        rz.validate_plan(manifest, plan)
        manifest['topology'] = 'multi-az-ha'
        with self.assertRaises(rz.InventoryError):
            rz.validate_plan(manifest, plan)
        multi, multi_plan = test_plan_binding.PlanBindingTests().fixture()
        multi['resources']['ecs_instance_ids'] = {'zone_a': 'i-a', 'zone_b': 'i-b'}
        multi['topology'] = 'single-az'
        with self.assertRaises(rz.InventoryError):
            rz.validate_plan(multi, multi_plan)

    def test_preflight_accepts_both_topologies_and_rejects_wrong_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            test_script_contracts.ScriptContracts.valid_manifest(path)
            original = json.loads(path.read_text())
            original.update(environment='auto-wonder-prod', stateMode='remote', sizePreset='small',
                            executionMode='unattended', accountUid='1234567890123456')
            original['tags']['Environment'] = original['environment']
            command = ['bash', str(ROOT / 'scripts/preflight.sh'), '--manifest', str(path),
                       '--source-dir', str(ROOT.parents[1]), '--dry-run']
            for topology, region in [('single-az', 'cn-hangzhou'), ('multi-az-ha', 'cn-hangzhou'),
                                     ('single-az', 'ap-southeast-1'), ('multi-az-ha', 'ap-southeast-1')]:
                data = copy.deepcopy(original)
                data['region'] = region
                data['topology'] = data['tags']['Topology'] = topology
                path.write_text(json.dumps(data))
                run = subprocess.run(command, text=True, capture_output=True)
                self.assertEqual(0, run.returncode, run.stderr)
                data['tags']['Topology'] = 'wrong-topology'
                path.write_text(json.dumps(data))
                run = subprocess.run(command, text=True, capture_output=True)
                self.assertNotEqual(0, run.returncode)
                self.assertIn('required system tags are invalid', run.stderr)

    def teardown_fixture(self):
        fixture = test_teardown_plan.TeardownPlanTests()
        fixture.setUp()
        fixture.manifest['topology'] = 'single-az'
        fixture.manifest['resources']['ecs_instance_ids'] = {'zone_a': 'i-a'}
        fixture.plan['resource_changes'] = [row for row in fixture.plan['resource_changes']
                                           if row['address'] != 'alicloud_instance.app["zone_b"]']
        for row in fixture.plan['resource_changes']:
            for side in ('before', 'after'):
                row['change'][side]['tags']['Topology'] = 'single-az'
        return fixture

    def test_teardown_accepts_single_node_owned_resources(self):
        fixture = self.teardown_fixture()
        self.assertEqual(8, fixture.module.review(fixture.manifest, fixture.plan)['updatedResources'])

    def test_teardown_rejects_wrong_topology_tag_or_extra_node_inventory(self):
        for invalid in ('tag', 'node'):
            fixture = self.teardown_fixture()
            if invalid == 'tag':
                fixture.plan['resource_changes'][0]['change']['before']['tags']['Topology'] = 'multi-az-ha'
            else:
                fixture.manifest['resources']['ecs_instance_ids']['zone_b'] = 'i-b'
            with self.subTest(invalid=invalid), self.assertRaises(fixture.module.ReviewError):
                fixture.module.review(fixture.manifest, fixture.plan)


if __name__ == '__main__':
    unittest.main()
