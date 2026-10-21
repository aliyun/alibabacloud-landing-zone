"""ALB zone selection must preserve business placement and owned VSwitches."""
import copy
import ipaddress
import unittest

import test_plan_binding
import test_resource_inventory
import test_teardown_plan

rz = test_plan_binding.rz


class IndependentAlbZones(unittest.TestCase):
    def test_existing_alb_pair_remains_valid_when_new_zone_stock_appears(self):
        _, policy, inventory = test_resource_inventory.CandidateTests().fixture()
        proof = inventory['alb'][0]['evidence']
        inventory['alb'] = [{'zone': zone, 'evidence': proof} for zone in ('a', 'c')]
        selected = rz.select_candidate(inventory, policy, {})
        manifest = dict(selected, resources={'load_balancer_id': 'alb-existing'})
        inventory['alb'].append({'zone': 'b', 'evidence': proof})
        existing = rz.existing_constraints(manifest)
        self.assertEqual('verified', rz.validate_candidate(selected, inventory, policy, existing)['status'])
        self.assertEqual(['a', 'c'], rz.select_candidate(inventory, policy, existing)['resolvedInfrastructure']['zonePlan']['alb']['zones'])

    def fixture(self):
        manifest, plan = test_plan_binding.PlanBindingTests().fixture()
        manifest['resolvedInfrastructure']['zonePlan']['alb']['zones'] = ['a', 'c']
        selected = rz.candidate_from_manifest(manifest)
        manifest['resourceSelection']['selected'] = selected
        manifest['resourceSelection']['selectionSha256'] = rz.digest({
            'policy': rz.effective_policy(manifest), 'selected': selected, 'existing': {}})
        plan['variables']['alb_zone_ids'] = {'value': ['a', 'c']}
        plan['variables']['additional_zone_cidrs'] = {'value': {'c': '10.0.0.0/24'}}
        alb = next(row for row in plan['resource_changes'] if row['type'] == 'alicloud_alb_load_balancer')
        alb['change']['after']['zone_mappings'] = [{'zone_id': 'a'}, {'zone_id': 'c'}]
        plan['resource_changes'].append({'address': 'alicloud_vswitch.additional["c"]',
            'type': 'alicloud_vswitch', 'mode': 'managed',
            'change': {'actions': ['create'], 'before': None,
                       'after': {'zone_id': 'c', 'cidr_block': '10.0.0.0/24'}}})
        return manifest, plan

    def test_tfvars_allocate_third_switch_inside_vpc_without_overlap(self):
        manifest, _ = self.fixture()
        values = rz.expected_tfvars(manifest)
        self.assertEqual(['a', 'c'], values['alb_zone_ids'])
        self.assertEqual({'c': '10.0.0.0/24'}, values['additional_zone_cidrs'])
        subnet = ipaddress.ip_network(values['additional_zone_cidrs']['c'])
        self.assertTrue(subnet.subnet_of(ipaddress.ip_network('10.0.0.0/16')))
        self.assertFalse(subnet.overlaps(ipaddress.ip_network('10.0.1.0/24')))
        self.assertFalse(subnet.overlaps(ipaddress.ip_network('10.0.2.0/24')))
        self.assertEqual({'zone_a': 'a', 'zone_b': 'b'}, values['ecs_nodes'])

    def test_default_two_zone_layout_adds_no_optional_tfvars(self):
        manifest, _ = test_plan_binding.PlanBindingTests().fixture()
        values = rz.expected_tfvars(manifest)
        self.assertNotIn('additional_zone_cidrs', values)
        self.assertNotIn('alb_zone_ids', values)

    def test_saved_plan_accepts_independent_alb_and_third_switch(self):
        manifest, plan = self.fixture()
        rz.validate_plan(manifest, plan)

    def test_saved_plan_rejects_wrong_alb_pair_missing_switch_and_overlapping_cidr(self):
        manifest, original = self.fixture()
        for invalid in ('alb-pair', 'missing-switch', 'cidr'):
            plan = copy.deepcopy(original)
            if invalid == 'alb-pair':
                alb = next(row for row in plan['resource_changes'] if row['type'] == 'alicloud_alb_load_balancer')
                alb['change']['after']['zone_mappings'] = [{'zone_id': 'a'}, {'zone_id': 'b'}]
            elif invalid == 'missing-switch':
                plan['resource_changes'].pop()
            else:
                plan['resource_changes'][-1]['change']['after']['cidr_block'] = '10.0.1.0/24'
            with self.subTest(invalid=invalid), self.assertRaises(rz.InventoryError):
                rz.validate_plan(manifest, plan)

    def teardown_fixture(self):
        fixture = test_teardown_plan.TeardownPlanTests()
        fixture.setUp()
        fixture.manifest['availabilityZones'] = ['a', 'b']
        fixture.manifest['resolvedInfrastructure'] = {'zonePlan': {'alb': {'zones': ['a', 'c']}}}
        switch = copy.deepcopy(fixture.changes['alicloud_vswitch.zone_a'])
        switch['address'] = 'alicloud_vswitch.additional["c"]'
        for side in ('before', 'after'):
            switch['change'][side].update(id='vsw-third', zone_id='c', cidr_block='10.0.0.0/24')
        fixture.plan['resource_changes'].append(switch)
        return fixture

    def test_teardown_accepts_exact_extra_alb_switch(self):
        fixture = self.teardown_fixture()
        self.assertEqual(8, fixture.module.review(fixture.manifest, fixture.plan)['updatedResources'])

    def test_teardown_rejects_foreign_tags_and_unknown_extra_switch_zone(self):
        for invalid in ('tag', 'zone'):
            fixture = self.teardown_fixture()
            switch = fixture.plan['resource_changes'][-1]
            if invalid == 'tag':
                switch['change']['before']['tags']['DeploymentId'] = 'foreign-deployment'
            else:
                switch['address'] = 'alicloud_vswitch.additional["d"]'
            with self.subTest(invalid=invalid), self.assertRaises(fixture.module.ReviewError):
                fixture.module.review(fixture.manifest, fixture.plan)


if __name__ == '__main__':
    unittest.main()
