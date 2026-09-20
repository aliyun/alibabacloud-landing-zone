import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import resolve_zones as rz
import test_resource_inventory as fixtures


class PlanBindingTests(unittest.TestCase):
    def test_existing_redis_readback_alias_is_not_a_topology_change(self):
        m, p = self.fixture()
        redis = next(r for r in p['resource_changes'] if r['type']=='alicloud_kvstore_instance')
        redis['change']['actions']=['no-op']
        redis['change']['after']['node_type']='double'
        rz.validate_plan(m,p)
        redis['change']['actions']=['create']
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m,p)
        redis['change']['actions']=['no-op']
        redis['change']['after']['node_type']='single'
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m,p)

    def test_vpc_internal_rule_allowed_but_public_or_other_vpc_access_rejected(self):
        m, p = self.fixture()
        rule = dict(type='ingress', ip_protocol='all', nic_type='intranet', policy='accept',
                    port_range='-1/-1', cidr_ip='10.0.0.0/16')
        p['resource_changes'].append({'type':'alicloud_security_group_rule',
                                     'change':{'actions':['create'],'after':rule}})
        rz.validate_plan(m,p)
        for cidr, port in [('0.0.0.0/0','-1/-1'), ('10.99.0.0/16','-1/-1'), ('1.2.3.4/32','22/22')]:
            rule.update(cidr_ip=cidr,port_range=port)
            with self.subTest(cidr=cidr), self.assertRaises(rz.InventoryError): rz.validate_plan(m,p)

    def test_redis_plan_requires_explicit_primary_replica_and_one_shard(self):
        m, p = self.fixture()
        for key, invalid in [('node_type','SINGLE'), ('shard_count',2)]:
            q = copy.deepcopy(p)
            row = next(r for r in q['resource_changes'] if r['type']=='alicloud_kvstore_instance')
            row['change']['after'][key] = invalid
            with self.subTest(key=key), self.assertRaises(rz.InventoryError): rz.validate_plan(m,q)

    def fixture(self):
        _, policy, inventory = fixtures.CandidateTests().fixture()
        selected = rz.select_candidate(inventory, policy, {})
        manifest = dict(selected, region='cn-beijing', accountUid='1234567890123456', resources={},
                        environment='auto-wonder-prod', deploymentId='test', lifecycle='persistent',
                        billing=policy['billing'], network={'vpcCidr':'10.0.0.0/16','zoneACidr':'10.0.1.0/24','zoneBCidr':'10.0.2.0/24'},
                        tags={}, publicSourceCidrs=['1.2.3.4/32'])
        manifest['resourceSelection'] = {'status':'verified','policy':policy,'selected':selected,
                                       'selectionSha256':rz.digest({'policy':policy,'selected':selected,'existing':{}})}
        variables = {k:{'value':v} for k,v in rz.expected_tfvars(manifest).items()}
        changes=[]
        for kind, vals in rz.expected_resources(manifest).items():
            for index, value in enumerate(vals):
                changes.append({'address':kind+'.r'+str(index),'type':kind,'mode':'managed',
                                'change':{'actions':['create'],'before':None,'after':value}})
        changes[-1]['change']['after']['zone_mappings']=[{'zone_id':z} for z in manifest['availabilityZones']]
        return manifest, {'variables':variables,'resource_changes':changes}

    def test_actual_plan_variables_and_resources_must_match(self):
        m,p=self.fixture()
        rz.validate_plan(m,p)
        for change in ('variable','sku','delete','unknown','same-zone','same-switch'):
            q=copy.deepcopy(p)
            if change=='same-switch':
                for row in q['resource_changes']:
                    if row['type']=='alicloud_vswitch': row['change']['after']['id']='vsw-'+row['change']['after']['zone_id']
                for row in q['resource_changes'][:2]: row['change']['after']['vswitch_id']='vsw-a'
            if change=='same-zone': q['resource_changes'][1]['change']['after']['availability_zone']='a'
            if change=='variable': q['variables']['region']['value']='cn-shanghai'
            if change=='sku': q['resource_changes'][0]['change']['after']['instance_type']='fake'
            if change=='delete': q['resource_changes'][0]['change']['actions']=['delete','create']
            if change=='unknown': q['resource_changes'][0]['change']['after']['image_id']=None
            with self.subTest(change=change), self.assertRaises(rz.InventoryError): rz.validate_plan(m,q)

    def update_fixture(self):
        m, p = self.fixture()
        m['mode'] = 'new'
        p['resource_changes'].append({
            'address': 'alicloud_alb_server_group.app', 'type': 'alicloud_alb_server_group',
            'mode': 'managed', 'change': {'actions': ['update'],
                'before': {'id': 'sg-existing', 'servers': [{'server_id': 'i-a', 'weight': 50}]},
                'after': {'id': 'sg-existing', 'servers': [{'server_id': 'i-a', 'weight': 100}]}}})
        return m, p

    def test_new_deployment_and_partial_resume_allow_updates(self):
        m, p = self.update_fixture()
        for resources in ({}, {'ecs_instance_ids': {'zone_a': 'i-a', 'zone_b': 'i-b'}}):
            m['resources'] = resources
            m['resourceSelection']['selectionSha256'] = rz.digest({
                'policy': rz.effective_policy(m), 'selected': rz.candidate_from_manifest(m),
                'existing': rz.existing_constraints(m)})
            rz.validate_plan(m, p)
            rz.validate_update_approval(m, p, 'a' * 64, '')

    def test_existing_updates_can_be_planned_but_need_exact_confirmation_to_apply(self):
        for evidence in ({'mode': 'upgrade'}, {'mode': 'operations'}, {'mode': None},
                         {'deployment': {'acceptedAt': '2026-01-01'}},
                         {'business': {'handoffConfirmed': True}},
                         {'acceptance': {'health': 'passed'}}, {'upgrade': {'toCommit': 'abc'}},
                         {'terraform': {'existingDeployment': True}}):
            m, p = self.update_fixture()
            m.update(evidence)
            rz.validate_plan(m, p)
            for confirmation in ('', 'b' * 64):
                with self.subTest(evidence=evidence, confirmation=confirmation):
                    with self.assertRaisesRegex(rz.InventoryError, 'update-confirmation-required'):
                        rz.validate_update_approval(m, p, 'a' * 64, confirmation)
            rz.validate_update_approval(m, p, 'a' * 64, 'a' * 64)

    def test_plan_preserves_existing_classification_when_status_is_overwritten(self):
        from unittest.mock import patch
        m, p = self.update_fixture()
        m['status'] = 'accepted'
        with tempfile.TemporaryDirectory() as td:
            work = Path(td)
            (work / 'reviewed.tfplan').write_bytes(b'plan')
            plan_path = work / 'plan.json'
            plan_path.write_text(json.dumps(p))
            with patch.object(rz, 'load_manifest', return_value=m), patch.object(rz, 'save_manifest'):
                rz.check_plan(work / 'manifest.json', plan_path, work)
            self.assertTrue(m['terraform']['existingDeployment'])
            m['status'] = 'awaiting-machine-review'
            with self.assertRaisesRegex(rz.InventoryError, 'update-confirmation-required'):
                rz.validate_update_approval(m, p, 'a' * 64, '')

    def test_new_deployment_computed_servers_update_is_allowed(self):
        m, p = self.update_fixture()
        change = p['resource_changes'][-1]['change']
        change['before'] = copy.deepcopy(change['after'])
        change['before']['servers'][0]['status'] = 'Available'
        change['after_unknown'] = {'servers': [{'status': True}]}
        rz.validate_plan(m, p)
        rz.validate_update_approval(m, p, 'a' * 64, '')

    def test_existing_noop_needs_no_update_confirmation(self):
        m, p = self.fixture()
        rz.validate_update_approval(m, p, 'a' * 64, '')

    def test_updates_still_obey_resource_and_action_guards(self):
        m, p = self.update_fixture()
        for actions in (['delete'], ['delete', 'create'], ['create', 'delete']):
            p['resource_changes'][-1]['change']['actions'] = actions
            with self.assertRaises(rz.InventoryError): rz.validate_plan(m, p)
        p['resource_changes'][-1]['change']['actions'] = ['update']
        p['resource_changes'][0]['change']['after']['instance_type'] = 'unapproved-size'
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m, p)

    def test_explicit_scaleout_targets_are_bound_and_require_confirmation(self):
        m, p = self.fixture()
        m['targetEcsNodes'] = {'zone_a': m['availabilityZones'][0], 'zone_b': m['availabilityZones'][1],
                               'worker_3': m['availabilityZones'][0]}
        p['variables']['ecs_nodes'] = {'value': m['targetEcsNodes']}
        for index, row in enumerate(p['resource_changes'][:2]):
            row['address'] = 'alicloud_instance.app["zone_' + ('a' if index == 0 else 'b') + '"]'
        extra = copy.deepcopy(p['resource_changes'][0])
        extra['address'] = 'alicloud_instance.app["worker_3"]'
        p['resource_changes'].append(extra)
        rz.validate_plan(m, p)
        with self.assertRaisesRegex(rz.InventoryError, 'update-confirmation-required'):
            rz.validate_update_approval(m, p, 'a' * 64, '')
        rz.validate_update_approval(m, p, 'a' * 64, 'a' * 64)
        extra['address'] = 'alicloud_instance.app["unapproved"]'
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m, p)

    def test_scaleout_quote_counts_each_intended_node_and_enforces_budget(self):
        m, _ = self.fixture()
        m['targetEcsNodes'] = {'zone_a': 'a', 'zone_b': 'b', 'third': 'a'}
        result = {'corePrice': {'amount': 100, 'currency': 'CNY'}, 'evidence': {'ecs': [
            {'zone': 'a', 'price': {'amount': 10, 'currency': 'CNY'}},
            {'zone': 'b', 'price': {'amount': 20, 'currency': 'CNY'}}]}}
        rz.price_intended_nodes(m, result, {})
        self.assertEqual(result['corePrice']['amount'], 110)
        result['corePrice']['amount'] = 100
        with self.assertRaisesRegex(rz.InventoryError, 'budget'):
            rz.price_intended_nodes(m, result, {'budget': {'monthlyLimit': 105, 'currency': 'CNY'}})

    def test_acl_removal_is_scoped_to_owned_acl_and_final_cidrs(self):
        m, p = self.fixture()
        p['resource_changes'].append({'address': 'alicloud_alb_acl.public_sources', 'type': 'alicloud_alb_acl',
            'change': {'actions': ['no-op'], 'before': {'id': 'acl-owned'}, 'after': {'id': 'acl-owned'}}})
        for cidr, actions in [('1.2.3.4/32', ['no-op']), ('5.6.7.8/32', ['delete'])]:
            value = {'acl_id': 'acl-owned', 'entry': cidr}
            p['resource_changes'].append({'address': 'alicloud_alb_acl_entry_attachment.public_sources[' + json.dumps(cidr) + ']',
                'type': 'alicloud_alb_acl_entry_attachment', 'change': {'actions': actions,
                'before': value, 'after': value if actions == ['no-op'] else None}})
        rz.validate_plan(m, p)
        with self.assertRaisesRegex(rz.InventoryError, 'update-confirmation-required'):
            rz.validate_update_approval(m, p, 'a' * 64, '')
        rz.validate_update_approval(m, p, 'a' * 64, 'a' * 64)
        p['resource_changes'][-1]['change']['before']['acl_id'] = 'acl-foreign'
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m, p)
        p['resource_changes'][-1]['change']['before']['acl_id'] = 'acl-owned'
        p['resource_changes'][-2]['change']['after']['entry'] = '0.0.0.0/0'
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m, p)

    def test_acl_replacement_keeps_final_set_and_unrelated_deletes_blocked(self):
        m, p = self.fixture()
        p['resource_changes'].append({'address': 'alicloud_alb_acl.public_sources', 'type': 'alicloud_alb_acl',
            'change': {'actions': ['no-op'], 'before': {'id': 'acl-owned'}, 'after': {'id': 'acl-owned'}}})
        value = {'acl_id': 'acl-owned', 'entry': '1.2.3.4/32'}
        p['resource_changes'].append({'address': 'alicloud_alb_acl_entry_attachment.public_sources["1.2.3.4/32"]',
            'type': 'alicloud_alb_acl_entry_attachment', 'change': {'actions': ['delete', 'create'],
            'before': value, 'after': dict(value, description='new')}})
        rz.validate_plan(m, p)
        with self.assertRaisesRegex(rz.InventoryError, 'update-confirmation-required'):
            rz.validate_update_approval(m, p, 'a' * 64, '')
        p['resource_changes'][0]['change']['actions'] = ['delete']
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m, p)

    def test_tag_update_of_recorded_instance_does_not_require_stock(self):
        m, p = self.fixture()
        m['resources']['ecs_instance_ids'] = {'zone_a': 'i-a', 'zone_b': 'i-b'}
        for index, row in enumerate(p['resource_changes'][:2]):
            row['change']['actions'] = ['update']
            row['change']['after']['id'] = ['i-a', 'i-b'][index]
            row['change']['before'] = copy.deepcopy(row['change']['after'])
            row['change']['after']['tags'] = {'owner': 'new'}
        self.assertTrue(rz.non_purchase_changes(m, p, 'alicloud_instance'))
        p['resource_changes'][0]['change']['after']['instance_type'] = 'other'
        self.assertFalse(rz.non_purchase_changes(m, p, 'alicloud_instance'))
        p['resource_changes'][0]['change']['after']['instance_type'] = p['resource_changes'][0]['change']['before']['instance_type']
        p['resource_changes'][0]['change']['before']['id'] = 'i-foreign'
        self.assertFalse(rz.non_purchase_changes(m, p, 'alicloud_instance'))

    def test_binding_detects_manifest_and_configuration_changes(self):
        m,p=self.fixture()
        with tempfile.TemporaryDirectory() as td:
            work=Path(td); (work/'main.tf').write_text('resource')
            (work/'reviewed.tfplan').write_bytes(b'plan')
            original=rz.plan_binding(m,work)
            m['publicSourceCidrs']=['0.0.0.0/0']
            self.assertNotEqual(original,rz.plan_binding(m,work))
            m['publicSourceCidrs']=['1.2.3.4/32']
            (work/'main.tf').write_text('changed')
            self.assertNotEqual(original,rz.plan_binding(m,work))

    def test_noop_existing_products_do_not_depend_on_retired_stock(self):
        from unittest.mock import patch
        import resource_inventory as inventory
        import test_resolve_zones as cloud
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'manifest.json'
            m,p=self.fixture()
            _, policy, facts=fixtures.CandidateTests().fixture()
            verdict=rz.validate_candidate(m['resourceSelection']['selected'],facts,policy,{})
            m['resourceSelection'].update(evidence=verdict['evidence'],corePrice=verdict['corePrice'])
            for row in p['resource_changes']: row['change']['actions']=['no-op']
            # Resource IDs become known after a partial apply; a new plan must rebind them.
            m['resources']={'ecs_instance_ids':{'zone_a':'i-existing-a','zone_b':'i-existing-b'}}
            path.write_text(json.dumps(m)); plan=Path(td)/'plan.json'; plan.write_text(json.dumps(p))
            def query(region,service,action,params):
                self.assertEqual(action,'GetCallerIdentity')
                return {'AccountId':m['accountUid']}
            with patch.object(inventory,'request',side_effect=query):
                self.assertEqual(rz.validate(path,plan),0)
    def test_metadata_update_validates_with_no_in_sale_catalogue(self):
        from unittest.mock import patch
        import resource_inventory as inventory
        import test_resolve_zones as cloud
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'manifest.json'
            m,p=self.fixture()
            _, policy, facts=fixtures.CandidateTests().fixture()
            verdict=rz.validate_candidate(m['resourceSelection']['selected'],facts,policy,{})
            m['resourceSelection'].update(evidence=verdict['evidence'],corePrice=verdict['corePrice'])
            for row in p['resource_changes']: row['change']['actions']=['no-op']
            # Resource IDs become known after a partial apply; a new plan must rebind them.
            m['resources']={'ecs_instance_ids':{'zone_a':'i-existing-a','zone_b':'i-existing-b'}}
            for index, row in enumerate(p['resource_changes'][:2]):
                row['change']['actions'] = ['update']
                row['change']['after']['id'] = ['i-existing-a', 'i-existing-b'][index]
                row['change']['before'] = copy.deepcopy(row['change']['after'])
                row['change']['after']['tags'] = {'owner': 'new'}
            path.write_text(json.dumps(m)); plan=Path(td)/'plan.json'; plan.write_text(json.dumps(p))
            def query(region,service,action,params):
                self.assertEqual(action,'GetCallerIdentity')
                return {'AccountId':m['accountUid']}
            with patch.object(inventory,'request',side_effect=query):
                self.assertEqual(rz.validate(path,plan),0)

if __name__=='__main__': unittest.main()
