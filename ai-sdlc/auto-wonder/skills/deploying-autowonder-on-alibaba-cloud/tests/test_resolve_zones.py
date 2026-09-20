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
