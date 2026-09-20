import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import resource_inventory as ri


class InventoryTests(unittest.TestCase):
    def test_token_pagination_is_complete(self):
        responses = [dict(InstanceTypes={'InstanceType': [{'InstanceTypeId': 'old'}]}, NextToken='next'),
                     dict(InstanceTypes={'InstanceType': [{'InstanceTypeId': 'new'}]})]
        with patch.object(ri, 'request', side_effect=responses) as query:
            result = ri.pages('cn-beijing', 'ecs', 'DescribeInstanceTypes', {}, ('InstanceTypes', 'InstanceType'))
        self.assertEqual([x['InstanceTypeId'] for x in result], ['old', 'new'])
        self.assertEqual(query.call_args.args[3]['NextToken'], 'next')

    def test_repeated_token_and_missing_data_are_unknown(self):
        for response in ({'NextToken': 'repeat', 'InstanceTypes': {'InstanceType': []}}, {}):
            with self.subTest(response=response), patch.object(ri, 'request', return_value=response):
                with self.assertRaises(ri.InventoryError):
                    ri.pages('cn-beijing', 'ecs', 'DescribeInstanceTypes', {}, ('InstanceTypes', 'InstanceType'))

    def test_failed_page_does_not_return_partial_results(self):
        with patch.object(ri, 'request', side_effect=[{'Items': [1], 'NextToken': 'n'}, ri.InventoryError('timeout')]):
            with self.assertRaises(ri.InventoryError):
                ri.pages('cn-beijing', 'ecs', 'List', {}, ('Items',))

    def test_cli_failure_redacted_and_not_retried_for_permission(self):
        failure = subprocess.CompletedProcess([], 1, '', 'Forbidden https://host/?SecurityToken=SECRET')
        with patch.object(ri.subprocess, 'run', return_value=failure) as run:
            with self.assertRaises(ri.InventoryError) as error:
                ri.request('cn-beijing', 'ecs', 'DescribeInstanceTypes', {})
        self.assertEqual(run.call_count, 1)
        self.assertNotIn('SECRET', str(error.exception))
        self.assertIn('permission', str(error.exception))
        self.assertIn('auto-wonder', run.call_args.args[0])

    def test_timeout_is_bounded(self):
        with patch.object(ri.subprocess, 'run', side_effect=subprocess.TimeoutExpired('aliyun', 30)) as run, patch.object(ri.time, 'sleep'):
            with self.assertRaises(ri.InventoryError):
                ri.request('cn-beijing', 'ecs', 'DescribeInstanceTypes', {})
        self.assertEqual(run.call_count, 3)

    def test_redis_price_uses_order_and_decimal_strings(self):
        self.assertEqual(ri.price({'Order':{'Currency':'CNY','OriginalAmount':'12.50','TradeAmount':'10.25'}},'redis')['amount'],12.5)
        for amount in ('NaN','Infinity','-1',True):
            with self.assertRaises(ri.InventoryError):
                ri.price({'Order':{'Currency':'CNY','OriginalAmount':amount}},'redis')

    def test_price_requires_currency_and_finite_nonnegative_amount(self):
        self.assertEqual(ri.price({'PriceInfo': {'Price': {'Currency': 'CNY', 'OriginalPrice': 10, 'TradePrice': 8}}})['amount'], 10)
        for value in ({}, {'PriceInfo': {'Currency': 'CNY', 'OriginalPrice': -1}},
                      {'PriceInfo': {'Currency': 'CNY', 'OriginalPrice': float('nan')}}):
            with self.assertRaises(ri.InventoryError):
                ri.price(value)

class CandidateTests(unittest.TestCase):
    def fixture(self):
        import resolve_zones as rz
        policy = json.loads((Path(__file__).resolve().parents[1] / 'assets/deployment-policy.json').read_text())
        proof = {'service': 'fixture', 'action': 'Describe', 'complete': True, 'queriedAt': '2026-09-19T00:00:00+00:00'}
        quote = {'amount': 10, 'currency': 'CNY'}
        inventory = {'complete': True, 'region': 'cn-beijing', 'unknown': [], 'limitations': [], 'images': [{'id': 'image', 'instanceType': 'ecs.new.large', 'evidence': proof}],
                     'alb': [{'zone': z, 'evidence': proof} for z in ('a','b')],
                     'ecs': [{'zone': z, 'type': 'ecs.new.large', 'cpu': 2, 'memoryGiB': 4, 'architecture': 'X86', 'familyLevel': 'EnterpriseLevel', 'price': quote, 'evidence': [proof]} for z in ('a','b')],
                     'rds': [{'zone': z, 'type': 'mysql.new', 'category': 'HighAvailability', 'storageType': 'cloud_essd', 'storageGiB': 100, 'cpu': 2, 'memoryGiB': 4, 'price': quote, 'evidence': [proof]} for z in ('a','b')],
                     'redis': [{'zone': z, 'type': 'redis.new', 'capacityMb': 1024, 'evidence': [proof], 'pairs': {other: {'price': quote, 'evidence': proof}}} for z,other in [('a','b'),('b','a')]]}
        return rz, policy, inventory

    def test_new_sku_complete_combination_and_stable_order(self):
        rz,p,i = self.fixture()
        c = rz.select_candidate(i,p,{})
        self.assertEqual(c['resolvedInfrastructure']['ecsInstanceType'], 'ecs.new.large')
        self.assertEqual(rz.validate_candidate(c,i,p,{})['status'], 'verified')
        i['ecs'].reverse()
        self.assertEqual(rz.select_candidate(i,p,{}), c)

    def test_unknown_downstream_and_missing_secondary_never_pass(self):
        rz,p,i = self.fixture()
        for field in ('rds','redis','alb','images'):
            with self.subTest(field=field):
                import copy
                broken = copy.deepcopy(i)
                broken[field] = broken[field][:1] if field != 'images' else []
                self.assertFalse(rz.select_candidate(broken,p,{}))
        i['complete'] = False
        self.assertFalse(rz.select_candidate(i,p,{}))

    def test_fabricated_candidate_capacity_and_existing_changes_rejected(self):
        rz,p,i = self.fixture()
        c = rz.select_candidate(i,p,{})
        import copy
        bad = copy.deepcopy(c)
        bad['resolvedInfrastructure']['ecsInstanceType'] = 'fabricated'
        self.assertNotEqual(rz.validate_candidate(bad,i,p,{})['status'], 'verified')
        self.assertNotEqual(rz.validate_candidate(c,i,p,{'availabilityZones':['a','c']})['status'], 'verified')
        i['ecs'][0]['memoryGiB'] = 8
        self.assertNotEqual(rz.validate_candidate(c,i,p,{})['status'], 'verified')

    def test_save_manifest_uses_cloud_checkpoint_and_propagates_failure(self):
        import tempfile
        rz,p,i = self.fixture()
        with tempfile.TemporaryDirectory() as td:
            path = Path(td)/'manifest.json'
            path.write_text('{}')
            with patch.object(rz, 'operations_assert_current') as before, patch.object(rz, 'operations_checkpoint', side_effect=RuntimeError('checkpoint failed')) as after:
                with self.assertRaises(RuntimeError):
                    rz.save_manifest(path, {'resourceSelection': {'policy': p}})
                before.assert_called_once()
                after.assert_called_once()

    def test_rds_and_cache_capacity_cannot_silently_change(self):
        rz,p,i=self.fixture()
        c=rz.select_candidate(i,p,{})
        i['redis'][0]['capacityMb']=2048
        i['redis'][1]['capacityMb']=2048
        self.assertNotEqual(rz.validate_candidate(c,i,p,{})['status'],'verified')

    def test_rds_storage_cannot_shrink_or_expand_outside_policy(self):
        import copy
        rz,p,i=self.fixture()
        for size in (20,200):
            changed=copy.deepcopy(i)
            for row in changed['rds']: row['storageGiB']=size
            self.assertFalse(rz.select_candidate(changed,p,{}))

    def test_budget_and_performance_class_are_hard_constraints(self):
        rz,p,i=self.fixture()
        p['budget']={'scope':'core-subscriptions','currency':'CNY','monthlyLimit':39}
        self.assertFalse(rz.select_candidate(i,p,{}))
        p.pop('budget')
        i['ecs'][0]['familyLevel']='CreditEntryLevel'
        self.assertFalse(rz.select_candidate(i,p,{}))

    def test_image_must_support_the_selected_type(self):
        rz,p,i=self.fixture()
        i['images'][0]['instanceType']='different-type'
        self.assertFalse(rz.select_candidate(i,p,{}))

    def test_redis_standby_can_use_evidenced_third_zone_without_third_switch(self):
        import copy
        rz,p,i=self.fixture()
        i['redis'][1]['zone']='c'
        i['redis'][0]['pairs']['c']=i['redis'][0]['pairs'].pop('b')
        c=rz.select_candidate(i,p,{})
        self.assertEqual(c['availabilityZones'],['a','b'])
        self.assertEqual(c['resolvedInfrastructure']['zonePlan']['redis']['secondaryZone'],'c')
        self.assertEqual(rz.validate_candidate(c,i,p,{})['status'],'verified')

    def test_database_capacity_missing_or_changed_is_not_equivalent(self):
        rz,p,i=self.fixture()
        i['rds'][0].pop('cpu')
        self.assertFalse(rz.select_candidate(i,p,{}))
        i['rds'][0]['cpu']=4
        self.assertFalse(rz.select_candidate(i,p,{}))

if __name__ == '__main__':
    unittest.main()
