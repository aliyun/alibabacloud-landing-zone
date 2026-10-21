"""Public entrypoints against deterministic, current Alibaba API response shapes."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import resolve_zones as rz
import resource_inventory as ri


def cloud_response(service, action, params):
    zones = ['cn-beijing-a','cn-beijing-b']
    if action == 'GetCallerIdentity':
        return {'AccountId':'1234567890123456'}
    if action == 'DescribeClassDetails':
        return {'Cpu':'2','MemoryClass':'4GB'}
    if service == 'r-kvstore' and action == 'DescribePrice':
        return {'Order':{'Currency':'CNY','OriginalAmount':'10.00','TradeAmount':'8.00'},'RequestId':'redis-quote'}
    if action == 'DescribePrice':
        return {'PriceInfo': {'Currency':'CNY','OriginalPrice':10,'TradePrice':8}, 'RequestId':'quote'}
    if service == 'alb':
        return {'Zones':[{'ZoneId':z} for z in zones]}
    if action == 'DescribeImages':
        return {'TotalCount':1,'Images':{'Image':[{'ImageId':'aliyun_3_fixture','Architecture':'x86_64','OSType':'linux','Status':'Available','ImageOwnerAlias':'system'}]}}
    if action == 'DescribeInstanceTypes':
        return {'InstanceTypes':{'InstanceType':[{'InstanceTypeId':'ecs.new.large','CpuCoreCount':2,'MemorySize':4,'CpuArchitecture':'X86','InstanceFamilyLevel':'EnterpriseLevel'}]}}
    if service == 'ecs' and action == 'DescribeAvailableResource':
        value = 'cloud_essd' if params['DestinationResource'] == 'SystemDisk' else 'ecs.new.large'
        return {'AvailableZones': {'AvailableZone':[{'ZoneId':z,'AvailableResources':{'AvailableResource':[{'SupportedResources':{'SupportedResource':[{'Value':value,'Status':'Available','StatusCategory':'WithStock','Min':20,'Max':2048,'Unit':'GiB'}]}}]}} for z in zones]}}
    if action == 'DescribeAvailableZones':
        return {'AvailableZones':[{'ZoneId':z,'SupportedEngines':[{'Engine':'MySQL','SupportedEngineVersions':[{'Version':'8.0','SupportedCategorys':[{'Category':'HighAvailability','SupportedStorageTypes':[{'StorageType':'cloud_essd'}]}]}]}]} for z in zones]}
    if action == 'DescribeAvailableClasses':
        return {'DBInstanceClasses':[{'DBInstanceClass':'mysql.new','DBInstanceStorageRange':{'MinValue':20,'MaxValue':2000,'Step':5}}]}
    if service == 'r-kvstore' and action == 'DescribeAvailableResource':
        leaf = {'AvailableResources':{'AvailableResource':[{'InstanceClass':'redis.new','Capacity':1024}]}}
        for outer,inner,field,value in reversed([
            ('SupportedEngines','SupportedEngine','Engine','Redis'),
            ('SupportedEditionTypes','SupportedEditionType','EditionType','Community'),
            ('SupportedSeriesTypes','SupportedSeriesType','SeriesType','standard'),
            ('SupportedEngineVersions','SupportedEngineVersion','Version','7.0'),
            ('SupportedArchitectureTypes','SupportedArchitectureType','Architecture','standard'),
            ('SupportedShardNumbers','SupportedShardNumber','ShardNumber','1'),
            ('SupportedNodeTypes','SupportedNodeType','SupportedNodeType','double')]):
            leaf = {outer:{inner:[dict(leaf,**{field:value})]}}
        return {'AvailableZones':{'AvailableZone':[dict(copy.deepcopy(leaf),ZoneId=z) for z in zones]}}
    raise AssertionError((service,action,params))


class ResolverIntegration(unittest.TestCase):
    def test_explicit_candidate_refreshes_only_selected_skus_and_rejects_real_stock_loss(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            self.manifest(path)
            with patch.object(ri, 'request', side_effect=cloud_response_adapter):
                self.assertEqual(0, rz.resolve(path))
            candidate = json.loads(path.read_text())['resourceSelection']['selected']
            candidate_path = Path(directory) / 'candidate.json'
            candidate_path.write_text(json.dumps(candidate))

            for lose_stock in (False, True):
                self.manifest(path)
                calls = []

                def query(region, service, action, params):
                    calls.append((service, action, params))
                    if action == 'DescribeInstanceTypes' and params.get('InstanceTypes.1') != 'ecs.new.large':
                        raise ri.InventoryError('unrelated-ecs-catalogue-api-error')
                    expected = {'InstanceType': 'ecs.new.large', 'ClassCode': 'mysql.new',
                                'DBInstanceClass': 'mysql.new', 'InstanceClass': 'redis.new'}
                    if any(key in params and params[key] != value for key, value in expected.items()):
                        raise ri.InventoryError('unrelated-sku-api-error')
                    if lose_stock and service == 'ecs' and action == 'DescribeAvailableResource':
                        return {'AvailableZones': {'AvailableZone': []}}
                    return cloud_response_adapter(region, service, action, params)

                with self.subTest(lose_stock=lose_stock), patch.object(ri, 'request', side_effect=query):
                    result = rz.resolve(path, candidate_file=candidate_path)
                    if lose_stock:
                        self.assertNotEqual(0, result)
                        self.assertNotEqual('verified', json.loads(path.read_text())['resourceSelection']['status'])
                    else:
                        self.assertEqual(0, result)
                        self.assertEqual(candidate, json.loads(path.read_text())['resourceSelection']['selected'])
                        self.assertTrue(any(action == 'DescribeInstanceTypes' and params.get('InstanceTypes.1') == 'ecs.new.large'
                                            for _, action, params in calls))

    def zone_fallback_query(self, calls, zone_status=None, disk_minimum=20):
        def query(region, service, action, params):
            calls.append((service, action, params))
            if service == 'ecs' and action == 'DescribeAvailableResource':
                destination = params['DestinationResource']
                if destination == 'Zone':
                    self.assertEqual('ecs.new.large', params['InstanceType'])
                    self.assertEqual('PrePaid', params['InstanceChargeType'])
                    status = {'Status': 'Available', 'StatusCategory': 'WithStock'} if zone_status is None else zone_status
                    return {'AvailableZones': {'AvailableZone': [dict(ZoneId=zone, **status)
                        for zone in ('cn-beijing-a', 'cn-beijing-b')]}}
                response = cloud_response(service, action, params)
                for zone in response['AvailableZones']['AvailableZone']:
                    if destination == 'InstanceType':
                        zone['AvailableResources']['AvailableResource'] = []
                    elif destination == 'SystemDisk':
                        zone['AvailableResources']['AvailableResource'][0]['SupportedResources']['SupportedResource'][0]['Min'] = disk_minimum
                return response
            return cloud_response(service, action, params)
        return query

    def test_empty_instance_type_resources_fall_back_to_explicit_zone_stock(self):
        for topology in ('single-az', 'multi-az-ha'):
            with self.subTest(topology=topology), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                data = self.manifest(path)
                data['topology'] = topology
                path.write_text(json.dumps(data))
                calls = []
                with patch.object(ri, 'request', side_effect=self.zone_fallback_query(calls)):
                    self.assertEqual(0, rz.resolve(path))
                self.assertEqual('verified', json.loads(path.read_text())['resourceSelection']['status'])
                destinations = [params.get('DestinationResource') for service, action, params in calls
                                if service == 'ecs' and action == 'DescribeAvailableResource']
                self.assertIn('Zone', destinations)
                self.assertIn('SystemDisk', destinations)

    def test_zone_fallback_requires_explicit_available_stock_status(self):
        for status in ({'Status': 'Available'}, {'StatusCategory': 'WithStock'},
                       {'Status': 'Available', 'StatusCategory': 'WithoutStock'},
                       {'Status': 'Unavailable', 'StatusCategory': 'WithStock'}):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'manifest.json'
                self.manifest(path)
                with patch.object(ri, 'request', side_effect=self.zone_fallback_query([], zone_status=status)):
                    self.assertNotEqual(0, rz.resolve(path))
                self.assertNotEqual('verified', json.loads(path.read_text())['resourceSelection']['status'])

    def test_zone_fallback_does_not_bypass_sixty_gib_system_disk_support(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            self.manifest(path)
            with patch.object(ri, 'request', side_effect=self.zone_fallback_query([], disk_minimum=100)):
                self.assertNotEqual(0, rz.resolve(path))
            self.assertNotEqual('verified', json.loads(path.read_text())['resourceSelection']['status'])

    def test_single_az_keeps_business_in_one_zone_and_alb_in_two(self):
        with tempfile.TemporaryDirectory() as td, patch.object(ri, 'request', side_effect=cloud_response_adapter):
            path = Path(td) / 'manifest.json'
            data = self.manifest(path)
            data['topology'] = 'single-az'
            path.write_text(json.dumps(data))
            self.assertEqual(rz.resolve(path), 0)
            data = json.loads(path.read_text())
            placement = data['resolvedInfrastructure']['zonePlan']
            self.assertEqual(placement['ecsZones'], ['cn-beijing-a'])
            self.assertEqual(placement['rds'], {'primaryZone': 'cn-beijing-a', 'slaveZone': 'cn-beijing-a'})
            self.assertEqual(placement['redis'], {'primaryZone': 'cn-beijing-a', 'secondaryZone': 'cn-beijing-a'})
            self.assertEqual(placement['alb']['zones'], ['cn-beijing-a', 'cn-beijing-b'])
            self.assertEqual(rz.intended_nodes(data), {'zone_a': 'cn-beijing-a'})
            self.assertEqual(data['resourceSelection']['corePrice']['amount'], 30)
            self.assertEqual(rz.validate(path), 0)

    def test_unknown_topology_is_rejected_before_discovery(self):
        with self.assertRaises(ri.InventoryError):
            rz.effective_policy({'region': 'cn-beijing', 'topology': 'typo'})

    def test_single_az_cannot_add_a_node_in_the_alb_only_zone(self):
        data = {'topology': 'single-az', 'availabilityZones': ['cn-beijing-a', 'cn-beijing-b'],
                'targetEcsNodes': {'zone_a': 'cn-beijing-a', 'worker': 'cn-beijing-b'}}
        with self.assertRaises(ri.InventoryError):
            rz.intended_nodes(data)

    def test_single_az_scale_out_counts_every_additional_node(self):
        data = {'topology': 'single-az', 'availabilityZones': ['cn-beijing-a', 'cn-beijing-b'],
                'targetEcsNodes': {'zone_a': 'cn-beijing-a', 'zone_b': 'cn-beijing-a'}}
        result = {'evidence': {'ecs': [{'zone': 'cn-beijing-a', 'price': {'amount': 10}}]},
                  'corePrice': {'amount': 30}}
        rz.price_intended_nodes(data, result, {})
        self.assertEqual(result['corePrice']['amount'], 40)

    def test_cloud_native_redis_uses_live_capacity_without_guessing_node_enum(self):
        data = cloud_response('r-kvstore', 'DescribeAvailableResource', {})
        def native(node):
            if isinstance(node, dict):
                if node.get('Engine') == 'Redis': node['Engine'] = 'redis'
                if node.get('Architecture') == 'standard': node['Architecture'] = 'non_cluster'
                node.pop('ShardNumber', None)
                if node.get('SupportedNodeType') == 'double': node['SupportedNodeType'] = '2'
                if 'InstanceClass' in node:
                    node['InstanceClass'] = 'redis.future'
                for value in node.values(): native(value)
            elif isinstance(node, list):
                for value in node: native(value)
        native(data)
        policy = json.loads((ROOT/'assets/deployment-policy.json').read_text())['redis']
        actual = ri.redis_classes(data, policy, product='OnECS')
        self.assertEqual([(r['type'], r['capacityMb']) for r in actual], [('redis.future',1024)]*2)
        def erase_capacity(node):
            if isinstance(node, dict):
                node.pop('Capacity', None)
                for value in node.values(): erase_capacity(value)
            elif isinstance(node, list):
                for value in node: erase_capacity(value)
        erase_capacity(data)
        excluded = []
        self.assertEqual(ri.redis_classes(data, policy, product='OnECS', excluded=excluded), [])
        self.assertTrue(excluded)

    def test_live_disk_and_localized_rds_metadata_resolve(self):
        def query(region, service, action, params):
            response = cloud_response(service, action, params)
            if service == 'ecs' and params.get('DestinationResource') == 'SystemDisk':
                for zone in response['AvailableZones']['AvailableZone']:
                    del zone['AvailableResources']['AvailableResource'][0]['SupportedResources']['SupportedResource'][0]['StatusCategory']
            if action == 'DescribeClassDetails':
                response['MemoryClass'] = ' 4GB（独享型）'
            return response
        with tempfile.TemporaryDirectory() as td, patch.object(ri, 'request', side_effect=query):
            path = Path(td) / 'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path), 0)

    def test_disk_capacity_bounds_exclude_unsupported_size(self):
        def query(region, service, action, params):
            response = cloud_response(service, action, params)
            if service == 'ecs' and params.get('DestinationResource') == 'SystemDisk':
                for zone in response['AvailableZones']['AvailableZone']:
                    zone['AvailableResources']['AvailableResource'][0]['SupportedResources']['SupportedResource'][0]['Min'] = 100
            return response
        with tempfile.TemporaryDirectory() as td, patch.object(ri, 'request', side_effect=query):
            path = Path(td) / 'manifest.json'; self.manifest(path)
            self.assertNotEqual(rz.resolve(path), 0)

    def test_unknown_rds_candidate_does_not_hide_verified_alternative(self):
        def query(region, service, action, params):
            response = cloud_response(service, action, params)
            if action == 'DescribeAvailableClasses':
                response['DBInstanceClasses'].insert(0, {'DBInstanceClass':'mysql.unknown'})
            if action == 'DescribeClassDetails':
                response = {'Cpu':'unknown','MemoryClass':''} if params['ClassCode']=='mysql.unknown' else {'Cpu':' 2 ', 'MemoryClass':' 4GB（独享型）'}
            return response
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=query):
            path=Path(td)/'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path),0)

    def test_explicit_disk_stock_loss_is_not_ignored(self):
        data = cloud_response('ecs','DescribeAvailableResource',{'DestinationResource':'SystemDisk'})
        for zone in data['AvailableZones']['AvailableZone']:
            zone['AvailableResources']['AvailableResource'][0]['SupportedResources']['SupportedResource'][0]['StatusCategory']='SoldOut'
        self.assertEqual(ri.stock(data,'cloud_essd',60),[])

    def manifest(self, path):
        data = {'region':'cn-beijing','accountUid':'1234567890123456','deploymentId':'test-deployment','mode':'new',
                'resolvedInfrastructure':{'rdsCategory':'HighAvailability','rdsStorageGb':100},'resources':{}}
        path.write_text(json.dumps(data))
        return data

    def test_discovery_resolve_validate_checkpoint_and_replanning_limit(self):
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=cloud_response_adapter):
            path = Path(td)/'manifest.json'
            self.manifest(path)
            self.assertEqual(rz.resolve(path),0)
            data = json.loads(path.read_text())
            self.assertEqual(data['resolvedInfrastructure']['ecsInstanceType'],'ecs.new.large')
            self.assertEqual(data['resourceSelection']['status'],'verified')
            self.assertEqual(rz.validate(path),0)
            self.assertEqual(rz.resolve(path),0)
            self.assertEqual(rz.resolve(path),0)
            self.assertEqual(rz.resolve(path),4)

    def test_missing_product_never_becomes_resolved(self):
        def query(region, service, action, params):
            if service == 'alb': raise ri.InventoryError('permission')
            return cloud_response(service,action,params)
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=query):
            path=Path(td)/'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path),3)
            self.assertNotIn('availabilityZones',json.loads(path.read_text()))

    def test_model_candidate_is_requeried_and_rejected(self):
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=cloud_response_adapter):
            root=Path(td); path=root/'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path),0)
            candidate=json.loads(path.read_text())['resourceSelection']['selected']
            candidate['resolvedInfrastructure']['ecsInstanceType']='ecs.fabricated'
            model=root/'candidate.json'; model.write_text(json.dumps(candidate))
            self.assertNotEqual(rz.resolve(path,candidate_file=model),0)

    def test_later_ecs_page_and_unrelated_response_fields_are_accepted(self):
        def query(region,service,action,params):
            response=cloud_response(service,action,params)
            if action=='DescribeInstanceTypes' and not params.get('NextToken'):
                return {'InstanceTypes':{'InstanceType':[]},'NextToken':'later','newOptionalField':True}
            return response
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=query):
            path=Path(td)/'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path),0)

    def test_known_retired_sku_does_not_poison_other_live_candidates(self):
        def query(region,service,action,params):
            result=cloud_response(service,action,params)
            if action=='DescribeInstanceTypes':
                old=copy.deepcopy(result['InstanceTypes']['InstanceType'][0]); old['InstanceTypeId']='ecs.retired'
                result['InstanceTypes']['InstanceType'].insert(0,old)
            if action=='DescribeAvailableResource' and params.get('InstanceType')=='ecs.retired':
                raise ri.InventoryError('unavailable')
            return result
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=query):
            path=Path(td)/'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path),0)

    def test_unknown_ecs_sku_response_does_not_hide_verified_alternative(self):
        def query(region, service, action, params):
            result = cloud_response(service, action, params)
            if action == 'DescribeInstanceTypes':
                unknown = dict(result['InstanceTypes']['InstanceType'][0], InstanceTypeId='ecs.unknown')
                result['InstanceTypes']['InstanceType'].insert(0, unknown)
            if action == 'DescribeAvailableResource' and params.get('InstanceType') == 'ecs.unknown':
                return {'RequestId':'unknown-stock'}
            return result
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=query):
            path=Path(td)/'manifest.json'; self.manifest(path)
            self.assertEqual(rz.resolve(path),0)

    def test_empty_template_defaults_are_discovered_not_treated_as_constraints(self):
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=cloud_response_adapter):
            path=Path(td)/'manifest.json'; data=self.manifest(path)
            data['resolvedInfrastructure']={'rdsCategory':'','rdsStorageGb':0,'ecsImageId':''}
            path.write_text(json.dumps(data))
            self.assertEqual(rz.resolve(path),0)

    def test_manifest_cannot_reduce_or_expand_rds_storage(self):
        with tempfile.TemporaryDirectory() as td, patch.object(ri,'request',side_effect=cloud_response_adapter):
            for size in (20,200):
                path=Path(td)/('manifest-'+str(size)+'.json'); data=self.manifest(path)
                data['resolvedInfrastructure']['rdsStorageGb']=size
                path.write_text(json.dumps(data))
                self.assertNotEqual(rz.resolve(path),0)

    def test_refresh_rejects_stock_loss_and_account_switch(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'manifest.json'; self.manifest(path)
            with patch.object(ri,'request',side_effect=cloud_response_adapter): self.assertEqual(rz.resolve(path),0)
            def query(region,service,action,params):
                result=cloud_response(service,action,params)
                if action=='DescribeAvailableResource' and service=='ecs':
                    return {'AvailableZones':{'AvailableZone':[]}}
                return result
            with patch.object(ri,'request',side_effect=query): self.assertEqual(rz.validate(path),4)
            def wrong_account(region,service,action,params):
                if action=='GetCallerIdentity': return {'AccountId':'different'}
                return cloud_response(service,action,params)
            with patch.object(ri,'request',side_effect=wrong_account): self.assertEqual(rz.validate(path),4)

    def test_public_wrapper_defaults_to_resolve_without_live_cloud(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); path=root/'manifest.json'; self.manifest(path)
            fake=root/'aliyun'
            fake.write_text('#!'+sys.executable+'\nimport sys,json\nsys.path.insert(0,'+repr(str(Path(__file__).parent))+')\nfrom test_resolve_zones import cloud_response\nargs=sys.argv[1:]; p=dict(zip([x.removeprefix("--") for x in args[2::2]],args[3::2]))\nprint(json.dumps(cloud_response(args[0],args[1],p)))\n')
            fake.chmod(0o700)
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],AUTOWONDER_PYTHON=sys.executable)
            run=subprocess.run(['bash',str(ROOT/'scripts/resolve-zones.sh'),'--manifest',str(path)],env=env,text=True,capture_output=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)


def cloud_response_adapter(region,service,action,params):
    return cloud_response(service,action,params)

if __name__=='__main__': unittest.main()
