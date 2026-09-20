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

    def test_existing_resource_update_is_not_an_automatic_replan(self):
        m,p=self.fixture()
        p['resource_changes'][0]['change'].update(actions=['update'],before={'id':'existing'})
        with self.assertRaises(rz.InventoryError): rz.validate_plan(m,p)

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

if __name__=='__main__': unittest.main()
