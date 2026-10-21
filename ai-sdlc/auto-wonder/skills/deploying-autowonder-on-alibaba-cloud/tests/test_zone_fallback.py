"""Placement priority and per-product HA fallbacks, independent of cloud credentials."""
import copy
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest

import test_plan_binding
import test_resource_inventory
import test_independent_alb_zones

rz = test_plan_binding.rz


def offers(inventory, product, zones):
    template = inventory[product][0]
    inventory[product] = [dict(copy.deepcopy(template), zone=z) for z in zones]
    if product == 'redis':
        pair = next(iter(template['pairs'].values()))
        for row in inventory[product]:
            row['pairs'] = {z: copy.deepcopy(pair) for z in zones if z != row['zone']}


class ZoneFallbackTests(unittest.TestCase):
    def test_each_product_prefers_same_then_overlap_then_disjoint(self):
        for product in ('alb', 'rds', 'redis'):
            for available, expected in [('abcd', {'a', 'b'}), ('bcde', {'b', 'c'}), ('cde', {'c', 'd'})]:
                _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
                offers(inventory, product, available)
                candidate = rz.select_candidate(inventory, policy, {})
                with self.subTest(product=product, available=available):
                    self.assertTrue(candidate)
                    plan = candidate['resolvedInfrastructure']['zonePlan']
                    self.assertEqual({'a', 'b'}, set(plan['ecsZones']))
                    self.assertEqual(expected, set(rz.product_pairs(plan)[product]))
                    self.assertEqual('verified', rz.validate_candidate(candidate, inventory, policy, {})['status'])
                    self.assertEqual(int(expected != {'a', 'b'}), len(plan['downgrades']))
                    if plan['downgrades']:
                        report = plan['downgrades'][0]
                        self.assertEqual(product, report['resource'])
                        self.assertEqual(sorted(expected & {'a', 'b'}), report['sharedZones'])
                        self.assertIn('constraints', report['reason'])
                        self.assertIn('one shared zone', report['selectionOrder'])

    def test_shared_pair_beats_cheaper_disjoint_ecs_pair(self):
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        offers(inventory, 'ecs', 'abcd')
        for row in inventory['ecs']:
            row['price']['amount'] = 100 if row['zone'] in 'ab' else 1
        selected = rz.select_candidate(inventory, policy, {})
        self.assertEqual(['a', 'b'], selected['availabilityZones'])
        self.assertEqual([], selected['resolvedInfrastructure']['zonePlan']['downgrades'])

    def test_one_shared_zone_beats_cheaper_disjoint_product_pair(self):
        for product in ('rds', 'redis'):
            _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
            offers(inventory, product, 'acde')
            if product == 'rds':
                for row in inventory['rds']:
                    row['type'] = 'mysql.overlap' if row['zone'] in 'ac' else 'mysql.disjoint'
                    row['price']['amount'] = 100 if row['zone'] in 'ac' else 1
            else:
                for row in inventory['redis']:
                    row['pairs'] = {z: value for z, value in row['pairs'].items()
                                    if (z in 'ac') == (row['zone'] in 'ac')}
                    for pair in row['pairs'].values():
                        pair['price']['amount'] = 100 if row['zone'] in 'ac' else 1
            selected = rz.select_candidate(inventory, policy, {})
            with self.subTest(product=product):
                self.assertEqual({'a', 'c'}, set(rz.product_pairs(selected['resolvedInfrastructure']['zonePlan'])[product]))

    def test_finds_common_pair_across_region_before_falling_back(self):
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        for product in ('ecs', 'rds', 'redis'):
            offers(inventory, product, 'abcd')
        offers(inventory, 'alb', 'cd')
        selected = rz.select_candidate(inventory, policy, {})
        self.assertEqual(['c', 'd'], selected['availabilityZones'])
        self.assertEqual([], selected['resolvedInfrastructure']['zonePlan']['downgrades'])

    def test_partial_and_disjoint_products_can_coexist_and_order_is_stable(self):
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        for product, zones in [('rds', 'cd'), ('redis', 'be'), ('alb', 'fg')]:
            offers(inventory, product, zones)
        selected = rz.select_candidate(inventory, policy, {})
        self.assertEqual(3, len(selected['resolvedInfrastructure']['zonePlan']['downgrades']))
        for product in ('ecs', 'rds', 'redis', 'alb'):
            inventory[product].reverse()
        self.assertEqual(selected, rz.select_candidate(inventory, policy, {}))
        self.assertEqual('verified', rz.validate_candidate(selected, inventory, policy, {})['status'])
        inventory['complete'] = False
        self.assertEqual({}, rz.select_candidate(inventory, policy, {}))

    def test_existing_placements_and_legacy_diagnostics_remain_valid(self):
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        for product in ('rds', 'redis', 'alb'):
            offers(inventory, product, 'cd')
        selected = rz.select_candidate(inventory, policy, {})
        selected['resolvedInfrastructure']['zonePlan']['downgrades'] = []
        manifest = dict(selected, resources={'load_balancer_id': 'alb-owned',
                        'rds': {'instance_id': 'db-owned'}, 'redis': {'instance_id': 'cache-owned'}})
        existing = rz.existing_constraints(manifest)
        for product in ('rds', 'redis', 'alb'):
            offers(inventory, product, 'abcd')
        self.assertEqual('verified', rz.validate_candidate(selected, inventory, policy, existing)['status'])
        actual = rz.select_candidate(inventory, policy, existing)
        self.assertEqual(rz.placement_identity(selected), rz.placement_identity(actual))

    def test_agent_candidate_cannot_override_automatic_priority(self):
        manifest, _ = test_plan_binding.PlanBindingTests().fixture()
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        for product in ('ecs', 'rds', 'redis', 'alb'):
            offers(inventory, product, 'abcd')
        inferior = next(c for c, *_ in rz.proposals(inventory, policy, {})
                         if c['availabilityZones'] == ['a', 'b']
                         and len(c['resolvedInfrastructure']['zonePlan']['downgrades']) == 2)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'candidate.json'
            path.write_text(json.dumps(inferior))
            with patch.object(rz, 'load_manifest', return_value=manifest), patch.object(rz, 'save_manifest'), \
                    patch.object(rz, 'collect_inventory', return_value=inventory), patch.object(rz, '_emit', return_value=0):
                self.assertEqual(0, rz.resolve('unused', candidate_file=path))
        self.assertEqual([], manifest['resolvedInfrastructure']['zonePlan']['downgrades'])

    def test_alb_ties_reuse_other_products_zones(self):
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        offers(inventory, 'rds', 'de')
        offers(inventory, 'redis', 'de')
        offers(inventory, 'alb', 'cde')
        selected = rz.select_candidate(inventory, policy, {})
        self.assertEqual(['d', 'e'], selected['resolvedInfrastructure']['zonePlan']['alb']['zones'])

    def plan_fixture(self):
        manifest, _ = test_plan_binding.PlanBindingTests().fixture()
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        for product, zones in [('rds', 'cd'), ('redis', 'ef'), ('alb', 'bg')]:
            offers(inventory, product, zones)
        selected = rz.select_candidate(inventory, policy, {})
        manifest.update(copy.deepcopy(selected))
        manifest['resourceSelection'].update(selected=selected, selectionSha256=rz.digest({
            'policy': policy, 'selected': selected, 'existing': {}}))
        variables = {k: {'value': v} for k, v in rz.expected_tfvars(manifest).items()}
        changes = [{'address': kind + '.r' + str(i), 'type': kind, 'mode': 'managed',
                    'change': {'actions': ['create'], 'after': value}}
                   for kind, values in rz.expected_resources(manifest).items() for i, value in enumerate(values)]
        for row in changes:
            after = row['change']['after']
            if row['type'] == 'alicloud_vswitch':
                after['id'] = 'vsw-' + after['zone_id']
            if row['type'] == 'alicloud_alb_load_balancer':
                after['zone_mappings'] = [{'zone_id': z, 'vswitch_id': 'vsw-' + z} for z in 'bg']
            if row['type'] == 'alicloud_db_instance':
                after['vswitch_id'] = 'vsw-c,vsw-d'
            if row['type'] == 'alicloud_kvstore_instance':
                after['vswitch_id'] = 'vsw-e'
        return manifest, {'variables': variables, 'resource_changes': changes}

    def test_tfvars_and_plan_cover_all_network_bearing_zones(self):
        manifest, plan = self.plan_fixture()
        values = rz.expected_tfvars(manifest)
        self.assertEqual('c', values['rds_primary_zone_id'])
        self.assertEqual('e', values['redis_primary_zone_id'])
        self.assertEqual({'c', 'd', 'e', 'g'}, set(values['additional_zone_cidrs']))
        self.assertEqual(4, len(set(values['additional_zone_cidrs'].values())))
        rz.validate_plan(manifest, plan)
        for kind in ('alicloud_db_instance', 'alicloud_kvstore_instance', 'alicloud_alb_load_balancer'):
            broken = copy.deepcopy(plan)
            row = next(r for r in broken['resource_changes'] if r['type'] == kind)
            if kind == 'alicloud_alb_load_balancer':
                row['change']['after']['zone_mappings'][0]['vswitch_id'] = 'vsw-a'
            else:
                row['change']['after']['vswitch_id'] = 'vsw-a'
            with self.subTest(kind=kind), self.assertRaises(rz.InventoryError):
                rz.validate_plan(manifest, broken)

    def test_existing_rds_primary_only_readback_is_preserved_but_new_orders_need_both(self):
        manifest, plan = self.plan_fixture()
        row = next(r for r in plan['resource_changes'] if r['type'] == 'alicloud_db_instance')
        row['change']['after'].update(id='db-owned', vswitch_id='vsw-c')
        row['change']['before'] = copy.deepcopy(row['change']['after'])
        manifest['resources']['rds'] = {'instance_id': 'db-owned'}
        manifest['resourceSelection']['selectionSha256'] = rz.digest({
            'policy': rz.effective_policy(manifest), 'selected': rz.candidate_from_manifest(manifest),
            'existing': rz.existing_constraints(manifest)})
        for action in ('no-op', 'update'):
            row['change']['actions'] = [action]
            rz.validate_plan(manifest, plan)
        row['change']['actions'] = ['create']
        with self.assertRaisesRegex(rz.InventoryError, 'switch-zone-mismatch'):
            rz.validate_plan(manifest, plan)
        row['change']['actions'] = ['no-op']
        row['change']['after']['vswitch_id'] = 'vsw-a'
        row['change']['before']['vswitch_id'] = 'vsw-a'
        with self.assertRaisesRegex(rz.InventoryError, 'switch-zone-mismatch'):
            rz.validate_plan(manifest, plan)

    def test_insufficient_subnet_space_blocks(self):
        manifest, _ = self.plan_fixture()
        manifest['network'].update(vpcCidr='10.0.0.0/24', zoneACidr='10.0.0.0/25', zoneBCidr='10.0.0.128/25')
        with self.assertRaisesRegex(rz.InventoryError, 'no-free-cidr'):
            rz.expected_tfvars(manifest)

    def test_teardown_recognizes_rds_and_redis_primary_switches_only(self):
        fixture = test_independent_alb_zones.IndependentAlbZones().teardown_fixture()
        fixture.manifest['resolvedInfrastructure']['zonePlan'] = {
            'alb': {'zones': ['a', 'b']}, 'rds': {'primaryZone': 'a', 'slaveZone': 'c'},
            'redis': {'primaryZone': 'c', 'secondaryZone': 'd'}}
        self.assertEqual(8, fixture.module.review(fixture.manifest, fixture.plan)['updatedResources'])
        fixture.plan['resource_changes'][-1]['address'] = 'alicloud_vswitch.additional["d"]'
        with self.assertRaises(fixture.module.ReviewError):
            fixture.module.review(fixture.manifest, fixture.plan)


if __name__ == '__main__':
    unittest.main()
