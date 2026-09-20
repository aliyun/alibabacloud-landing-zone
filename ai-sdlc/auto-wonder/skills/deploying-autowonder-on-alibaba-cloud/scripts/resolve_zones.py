#!/usr/bin/env python3
"""Resolve or validate Alibaba Cloud availability zones for AutoWonder.

Pure parsing/selection helpers are separated from CLI IO so the selection
logic can be unit-tested with canned API responses. Downstream (RDS/Redis)
facts must cover every selected product. Incomplete discovery is unknown,
never proof that a deployment is possible.

Exit codes: 0 resolved/valid, 2 usage/manifest error, 3 needs-agent,
4 blocked/invalid. The module never prints secrets.
"""
import argparse
import copy
import hashlib
import ipaddress
from itertools import permutations
import math
import json
from pathlib import Path
from operations_hooks import assert_current as operations_assert_current, checkpoint as operations_checkpoint
from operations_bundle import private_write
from resource_inventory import collect_inventory, InventoryError

POLICY_FILE = Path(__file__).resolve().parents[1] / 'assets/deployment-policy.json'


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def load_manifest(path):
    operations_assert_current(path)
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def save_manifest(path, data):
    operations_assert_current(path)
    private_write(path, (json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False) + '\n').encode())
    operations_checkpoint(path, data)


def effective_policy(data):
    default = json.loads(POLICY_FILE.read_text())
    policy = copy.deepcopy(data.get('resourceSelection', {}).get('policy') or default)
    if policy.get('version') != 1 or policy['ecs']['cpu'] != 2 or policy['ecs']['memoryGiB'] != 4 or policy['ecs']['architecture'] != 'X86':
        raise InventoryError('unsupported-policy')
    if any(policy.get(key) != default[key] for key in ('rds','redis','regions')):
        raise InventoryError('unsupported-product-policy')
    if any(policy['ecs'].get(key) != default['ecs'][key] for key in ('systemDisk','systemDiskGiB','familyLevel','defaultImagePrefix')):
        raise InventoryError('unsupported-disk-policy')
    if policy['billing'] != default['billing']:
        raise InventoryError('unsupported-billing-policy')
    if data.get('billing') and data['billing'] != policy['billing']:
        raise InventoryError('manifest-billing-differs-from-policy')
    if data.get('region') not in policy['regions']:
        raise InventoryError('unsupported-region')
    if data.get('budget'):
        budget = data['budget']
        if (budget.get('scope') != 'core-subscriptions' or budget.get('currency') not in ('CNY','USD')
                or isinstance(budget.get('monthlyLimit'), bool) or not isinstance(budget.get('monthlyLimit'), (int,float))
                or not math.isfinite(budget['monthlyLimit']) or budget['monthlyLimit'] < 0):
            raise InventoryError('unsupported-budget-contract')
        policy['budget'] = copy.deepcopy(budget)
    return policy


def candidate_from_manifest(data):
    return {'availabilityZones': copy.deepcopy(data.get('availabilityZones', [])),
            'resolvedInfrastructure': {k: copy.deepcopy(v) for k, v in data.get('resolvedInfrastructure', {}).items()
                                       if k in ('ecsInstanceType','ecsImageId','ecsVcpus','ecsMemoryGiB','rdsInstanceType',
                                                'rdsCategory','rdsStorageType','rdsStorageGb','redisInstanceClass','zonePlan')}}


def existing_constraints(data):
    """Freeze recorded resources; Terraform plan later checks real prior state too."""
    resources = data.get('resources') or {}
    original = candidate_from_manifest(data)
    result = {}
    keys = []
    if (resources.get('load_balancer_id') or resources.get('alb_id') or resources.get('ecs_instance_ids')
            or (resources.get('rds') or {}).get('instance_id') or (resources.get('redis') or {}).get('instance_id')):
        result['availabilityZones'] = original['availabilityZones']
    if resources.get('ecs_instance_ids'):
        keys += ['ecsInstanceType','ecsImageId','ecsVcpus','ecsMemoryGiB']
    if (resources.get('rds') or {}).get('instance_id'):
        keys += ['rdsInstanceType','rdsCategory','rdsStorageType','rdsStorageGb']
    if (resources.get('redis') or {}).get('instance_id'):
        keys += ['redisInstanceClass']
    result['placements'] = {product: copy.deepcopy(original['resolvedInfrastructure'].get('zonePlan', {}).get(product))
                            for product in ('rds','redis') if (resources.get(product) or {}).get('instance_id')}
    if not result['placements']: result.pop('placements')
    if keys:
        result['resolvedInfrastructure'] = {k: original['resolvedInfrastructure'].get(k) for k in keys}
    return result


def matches_existing(candidate, existing):
    if existing.get('availabilityZones') and candidate['availabilityZones'] != existing['availabilityZones']:
        return False
    if any(candidate['resolvedInfrastructure'].get('zonePlan', {}).get(k) != v for k,v in existing.get('placements', {}).items()):
        return False
    return all(candidate['resolvedInfrastructure'].get(k) == v for k,v in existing.get('resolvedInfrastructure', {}).items())


def proposals(inventory, policy, existing):
    if not inventory.get('complete') or not inventory.get('images'):
        return
    zones = sorted({row['zone'] for row in inventory['ecs']} & {row['zone'] for row in inventory['alb']})
    for a,b in permutations(zones, 2):
        for ecs in sorted(inventory['ecs'], key=lambda r: (r['type'],r['zone'])):
            if ecs['zone'] != a: continue
            images = [r for r in inventory['images'] if r.get('instanceType') == ecs['type']]
            fixed_image = existing.get('resolvedInfrastructure', {}).get('ecsImageId')
            if fixed_image: images = [r for r in images if r['id'] == fixed_image]
            if not images: continue
            image = max(images, key=lambda r: (r.get('createdAt', ''), r['id']))

            second = next((e for e in inventory['ecs'] if e['zone'] == b and e['type'] == ecs['type']), None)
            if second is None: continue
            if any((e.get('cpu'),e.get('memoryGiB'),e.get('architecture')) != (2,4,'X86') for e in (ecs,second)): continue
            if any(e.get('familyLevel') != policy['ecs']['familyLevel'] for e in (ecs,second)): continue
            for db in sorted(inventory['rds'], key=lambda r: (r['type'],r['zone'],r['storageType'])):
                if (db['zone'] != a or db['category'] not in policy['rds']['categories']
                        or db['storageGiB'] != policy['rds']['storageGiB'] or db['storageType'] not in policy['rds']['storageTypes']): continue
                db_second = next((r for r in inventory['rds'] if r['zone'] == b and all(r[k] == db[k] for k in ('type','category','storageType','storageGiB'))), None)
                if db_second is None: continue
                if any((r.get('cpu'), r.get('memoryGiB')) != (policy['rds']['cpu'], policy['rds']['memoryGiB']) for r in (db, db_second)): continue
                for cache in sorted(inventory['redis'], key=lambda r: (r['capacityMb'],r['type'],r['zone'])):
                    if cache['zone'] != a or cache['capacityMb'] != policy['redis']['capacityMb']: continue
                    for secondary in sorted(set(cache.get('pairs', {})) - {a}):
                        cache_second = next((r for r in inventory['redis'] if r['zone'] == secondary and r['type'] == cache['type'] and r['capacityMb'] == cache['capacityMb']), None)
                        if cache_second is None: continue
                        ri = dict(ecsInstanceType=ecs['type'], ecsImageId=image['id'], ecsVcpus=2, ecsMemoryGiB=4,
                                  rdsInstanceType=db['type'], rdsCategory=db['category'], rdsStorageType=db['storageType'],
                                  rdsStorageGb=db['storageGiB'], redisInstanceClass=cache['type'],
                                  zonePlan={'ecsZones':[a,b], 'rds':{'primaryZone':a,'slaveZone':b},
                                            'redis':{'primaryZone':a,'secondaryZone':secondary}, 'alb':{'zones':[a,b]},
                                            'downgrades':([] if secondary == b else [{'resource':'redis.secondary','from':b,'to':secondary}]), 'resolvedBy':'inventory'})
                        candidate = {'availabilityZones':[a,b], 'resolvedInfrastructure':ri}
                        if not matches_existing(candidate, existing): continue
                        quotes = [ecs['price'], second['price'], db['price'], cache['pairs'][secondary]['price']]
                        if len({q['currency'] for q in quotes}) != 1: continue
                        if any(not isinstance(q['amount'], (int,float)) or not math.isfinite(q['amount']) or q['amount'] < 0 for q in quotes): continue
                        budget = policy.get('budget')
                        if budget and (quotes[0]['currency'] != budget['currency'] or sum(q['amount'] for q in quotes) > budget['monthlyLimit']): continue
                        evidence = {'ecs':[ecs,second], 'rds':[db,db_second], 'redis':[cache,cache_second], 'image':image,
                                    'alb':[r for r in inventory['alb'] if r['zone'] in (a,b)],
                                    'rdsPlacement':{'primary':a,'secondary':b,'rule':'distinct MySQL HA cloud-disk zones with corresponding ordered VSwitches',
                                                    'source':'https://help.aliyun.com/zh/rds/developer-reference/api-rds-2014-08-15-createdbinstance',
                                                    'scope':'catalogue eligibility and supported placement rule; not order precheck'}, 'limitations':inventory.get('limitations',[])}
                        yield candidate, sum(q['amount'] for q in quotes), quotes[0]['currency'], evidence


def select_candidate(inventory, policy, existing):
    options = proposals(inventory, policy, existing)
    # Prefer the least expensive complete combination, with stable ties.
    best = min(options, key=lambda x: (x[1], len(x[0]['resolvedInfrastructure']['zonePlan']['downgrades']), x[0]['resolvedInfrastructure']['ecsInstanceType'] != policy['ecs']['preferred'],
                                       json.dumps(x[0], sort_keys=True)), default=None)
    return best[0] if best else {}


def validate_candidate(candidate, inventory, policy, existing):
    if not inventory.get('complete'):
        return {'status':'needs-agent', 'checks':[{'name':'inventory','status':'unknown'}], 'errors':inventory.get('unknown',[])}
    for allowed, amount, currency, evidence in proposals(inventory, policy, existing):
        if candidate == allowed:
            return {'status':'verified', 'checks':[{'name':name,'status':'passed'} for name in ('ecs','image-disk','rds','redis','alb','existing-resources','core-price')],
                    'evidence':evidence, 'corePrice':{'amount':amount,'currency':currency,'periodMonths':1,'scope':'core-subscriptions-only'}}
    return {'status':'blocked', 'checks':[{'name':'combination','status':'failed'}],
            'errors':['candidate is not a complete evidenced combination or changes an existing resource']}


def assert_settled(data):
    if data.get('terraform', {}).get('pendingOperation') or data.get('operationsMigration', {}).get('status') == 'unknown' or (data.get('status') == 'unknown' and data.get('phase') in ('infrastructure','terraform-destroy')):
        raise InventoryError('unresolved-cloud-operation')


def _emit(payload, code):
    print(json.dumps(payload, ensure_ascii=False))
    return code


def resolve(manifest_path, region_override=None, candidate_file=None):
    data = load_manifest(manifest_path)
    assert_settled(data)
    if region_override and region_override != data.get('region'):
        raise InventoryError('region-override-must-match-manifest')
    policy = effective_policy(data)
    old = data.get('resourceSelection', {})
    attempt = old.get('planningAttempts', 0) + 1
    if attempt > 3:
        return _emit({'status':'blocked','reason':'replanning limit reached; diagnose before requesting a new planning run'},4)
    existing = existing_constraints(data)
    inventory = collect_inventory(data, policy)
    candidate = json.loads(Path(candidate_file).read_text()) if candidate_file else select_candidate(inventory,policy,existing)
    validation = validate_candidate(candidate,inventory,policy,existing)
    selection = {'version':1,'policy':policy,'status':validation['status'],'checks':validation['checks'],
                 'planningAttempts':attempt,'errors':validation.get('errors',[])}
    if validation['status'] == 'verified':
        selection.update(selected=candidate,evidence=validation['evidence'],corePrice=validation['corePrice'],
                         selectionSha256=digest({'policy':policy,'selected':candidate,'existing':existing}),
                         evidenceSha256=digest(validation['evidence']))
        data['availabilityZones'] = candidate['availabilityZones']
        data.setdefault('resolvedInfrastructure',{}).update(candidate['resolvedInfrastructure'])
    else:
        # Keep previous applied coordinates; they remain the recovery basis.
        selection['status'] = 'needs-agent' if not candidate else validation['status']
    data['resourceSelection'] = selection
    save_manifest(manifest_path,data)
    return _emit({'status':'resolved' if selection['status']=='verified' else selection['status'],
                  'zones':data.get('availabilityZones',[]),'instanceType':data.get('resolvedInfrastructure',{}).get('ecsInstanceType'),
                  'errors':selection['errors']}, 0 if selection['status']=='verified' else (3 if selection['status']=='needs-agent' else 4))


def validate(manifest_path, plan_path=None):
    data = load_manifest(manifest_path)
    assert_settled(data)
    policy = effective_policy(data)
    candidate = candidate_from_manifest(data)
    # Do not use cached historical stock for validation or apply.
    query_data = dict(data, _refreshSelection=True)
    if plan_path and data.get('resourceSelection', {}).get('evidence'):
        plan = json.loads(Path(plan_path).read_text())
        validate_plan(data, plan, bind_selection=False)
        query_data['_existingPlanTypes'] = [kind for kind in ('alicloud_instance','alicloud_db_instance','alicloud_kvstore_instance','alicloud_alb_load_balancer')
                                           if all(r['change']['actions'] == ['no-op'] for r in plan['resource_changes'] if r.get('type') == kind)]
    inventory = collect_inventory(query_data, policy)
    result = validate_candidate(candidate, inventory, policy, existing_constraints(data))
    if result['status'] != 'verified':
        return _emit({'status':'invalid','errors':result.get('errors',[]),'checks':result['checks']},4)
    selection = data.get('resourceSelection', {})
    selection.update(version=1, policy=policy, status='verified', selected=candidate, evidence=result['evidence'], checks=result['checks'],
                     corePrice=result['corePrice'], evidenceSha256=digest(result['evidence']),
                     selectionSha256=digest({'policy':policy,'selected':candidate,'existing':existing_constraints(data)}))
    selection['existingPlanTypes'] = query_data.get('_existingPlanTypes', [])
    data['resourceSelection'] = selection
    save_manifest(manifest_path,data)
    return _emit({'status':'valid','zones':data['availabilityZones'],'instanceType':candidate['resolvedInfrastructure']['ecsInstanceType']},0)


def expected_tfvars(data):
    ri, network, billing = data['resolvedInfrastructure'], data['network'], data['billing']
    values = {k:data[k] for k in ('region','environment')}
    values.update(deployment_id=data['deploymentId'], zone_a_id=data['availabilityZones'][0], zone_b_id=data['availabilityZones'][1],
                  lifecycle_mode=data['lifecycle'], public_source_cidrs=data['publicSourceCidrs'], common_tags=data['tags'],
                  vpc_cidr=network['vpcCidr'], zone_a_cidr=network['zoneACidr'], zone_b_cidr=network['zoneBCidr'],
                  billing_strategy=billing['strategy'], purchase_period_months=billing['purchasePeriodMonths'],
                  auto_renew=billing['autoRenew'], auto_renew_period_months=billing['autoRenewPeriodMonths'])
    for target, source in [('ecs_image_id','ecsImageId'),('ecs_instance_type','ecsInstanceType'),('rds_instance_type','rdsInstanceType'),
                           ('rds_category','rdsCategory'),('rds_storage_type','rdsStorageType'),('rds_storage_gb','rdsStorageGb'),
                           ('redis_instance_class','redisInstanceClass')]:
        values[target] = ri[source]
    values['rds_slave_zone_id'] = ri['zonePlan']['rds']['slaveZone']
    values['redis_secondary_zone_id'] = ri['zonePlan']['redis']['secondaryZone']
    return values


def expected_resources(data):
    v = expected_tfvars(data)
    ecs = dict(image_id=v['ecs_image_id'], instance_type=v['ecs_instance_type'], system_disk_category='cloud_essd',
               system_disk_size=60, instance_charge_type='PrePaid', period=1, period_unit='Month',
               renewal_status='AutoRenewal', auto_renew_period=1, internet_max_bandwidth_out=0)
    return {
        'alicloud_instance':[dict(ecs, availability_zone=v['zone_a_id']), dict(ecs, availability_zone=v['zone_b_id'])],
        'alicloud_db_instance':[dict(engine='MySQL', engine_version='8.0', instance_type=v['rds_instance_type'],
                                    instance_storage=v['rds_storage_gb'], db_instance_storage_type=v['rds_storage_type'],
                                    category=v['rds_category'], instance_charge_type='Prepaid', period=1,
                                    auto_renew=True, auto_renew_period=1, zone_id=v['zone_a_id'], zone_id_slave_a=v['rds_slave_zone_id'])],
        'alicloud_kvstore_instance':[dict(instance_class=v['redis_instance_class'], instance_type='Redis', engine_version='7.0',
                                        zone_id=v['zone_a_id'], secondary_zone_id=v['redis_secondary_zone_id'],
                                        node_type='MASTER_SLAVE', shard_count=1,
                                        payment_type='PrePaid', period='1', auto_renew=True, auto_renew_period=1)],
        'alicloud_vswitch':[dict(zone_id=v['zone_a_id'], cidr_block=v['zone_a_cidr']), dict(zone_id=v['zone_b_id'], cidr_block=v['zone_b_cidr'])],
        'alicloud_vpc':[dict(cidr_block=v['vpc_cidr'])],
        'alicloud_alb_load_balancer':[dict(address_type='Internet', address_allocated_mode='Fixed', load_balancer_edition='Basic')],
    }


def validate_plan(data, plan, bind_selection=True):
    assert_settled(data)
    selection = data.get('resourceSelection', {})
    selected = candidate_from_manifest(data)
    policy = effective_policy(data)
    if (selection.get('status') != 'verified' or selection.get('selected') != selected
            or (bind_selection and selection.get('selectionSha256') != digest({'policy':policy,'selected':selected,'existing':existing_constraints(data)}))):
        raise InventoryError('selection-changed-revalidate-and-replan')
    for key, value in expected_tfvars(data).items():
        if plan.get('variables', {}).get(key, {}).get('value') != value:
            raise InventoryError('plan-input-mismatch:' + key)
    changes = plan.get('resource_changes')
    if not isinstance(changes, list): raise InventoryError('missing-plan-resources')
    actual = {}
    for resource in changes:
        if resource.get('mode') == 'data': continue
        change = resource['change']
        # This deployment path may finish creating resources, never mutate existing ones.
        if change.get('actions') not in (['create'], ['no-op']):
            raise InventoryError('plan-changes-existing-resource')
        kind, after = resource['type'], change.get('after') or {}
        if kind == 'alicloud_kvstore_instance' and change['actions'] == ['no-op'] and after.get('node_type') == 'double':
            after = dict(after, node_type='MASTER_SLAVE')
        if kind in ('alicloud_nat_gateway','alicloud_eip','alicloud_eip_address'):
            raise InventoryError('plan-adds-forbidden-network-resource')
        if kind == 'alicloud_security_group_rule':
            vpc = ipaddress.ip_network(data['network']['vpcCidr'])
            private_vpc = any(vpc.subnet_of(ipaddress.ip_network(cidr)) for cidr in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'))
            internal = private_vpc and all(after.get(k) == v for k,v in dict(
                cidr_ip=str(vpc), type='ingress', ip_protocol='all', nic_type='intranet', policy='accept').items())
            if (after.get('port_range') == '22/22' or after.get('cidr_ip') == '0.0.0.0/0'
                    or (after.get('port_range') == '-1/-1' and not internal)):
                raise InventoryError('plan-opens-forbidden-access')
        actual.setdefault(kind, []).append(after)
    for kind, expected in expected_resources(data).items():
        remaining = list(actual.get(kind, []))
        for fields in expected:
            match = next((r for r in remaining if all(r.get(k) == v for k,v in fields.items())), None)
            if match is None: raise InventoryError('plan-resource-mismatch:' + kind)
            remaining.remove(match)
        if remaining: raise InventoryError('plan-adds-extra-capacity:' + kind)
    for instance in actual['alicloud_instance']:
        switch_id = instance.get('vswitch_id')
        if switch_id is not None and not any(s.get('id') == switch_id and s.get('zone_id') == instance['availability_zone'] for s in actual['alicloud_vswitch']):
            raise InventoryError('plan-ecs-switch-zone-mismatch')
    for alb in actual['alicloud_alb_load_balancer']:
        # Zone mappings may contain unknown switch IDs, but zones must be known.
        if sorted(z.get('zone_id', '') for z in alb.get('zone_mappings', [])) != sorted(data['availabilityZones']):
            raise InventoryError('plan-alb-zone-mismatch')


def plan_binding(data, work):
    work = Path(work)
    files = {}
    for path in sorted(work.rglob('*')):
        relative = path.relative_to(work)
        if '.terraform' in relative.parts or not path.is_file(): continue
        if path.name.endswith(('.tf','.tf.json','.tfvars','.tfvars.json','.hcl')) or path.name == 'terraform-secrets.env':
            files[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {'version':1, 'selectionSha256':data['resourceSelection']['selectionSha256'],
            'inputsSha256':digest({'tfvars':expected_tfvars(data),'accountUid':data['accountUid'],
                                   'existing':existing_constraints(data),'policy':effective_policy(data),'corePrice':data['resourceSelection'].get('corePrice')}),
            'configurationSha256':digest(files),
            'planFingerprint':hashlib.sha256((work/'reviewed.tfplan').read_bytes()).hexdigest()}


def check_plan(manifest_path, plan_path, work, verify=False):
    data = load_manifest(manifest_path)
    plan = json.loads(Path(plan_path).read_text())
    validate_plan(data, plan)
    binding = plan_binding(data, work)
    if verify:
        if data.get('terraform', {}).get('selectionBinding') != binding:
            raise InventoryError('saved-plan-binding-changed-replan')
    else:
        data.setdefault('terraform', {})['selectionBinding'] = binding
        save_manifest(manifest_path, data)
    return _emit({'status':'valid','stage':'plan-binding'},0)


def main(argv=None):
    parser = argparse.ArgumentParser(description='Discover and validate a complete HA resource combination')
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('resolve','validate','discover','check-plan','check-binding'):
        child = sub.add_parser(command)
        child.add_argument('--manifest', required=True)
        if command == 'resolve':
            child.add_argument('--region')
            child.add_argument('--candidate')
        if command == 'validate':
            child.add_argument('--plan-json')
        if command == 'discover':
            child.add_argument('--output', required=True)
        if command in ('check-plan','check-binding'):
            child.add_argument('--plan-json', required=True)
            child.add_argument('--work-dir', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'discover':
            data = load_manifest(args.manifest)
            assert_settled(data)
            inventory = collect_inventory(data,effective_policy(data))
            private_write(args.output,(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n').encode())
            return _emit({'status':'complete' if inventory['complete'] else 'needs-agent','errors':inventory['unknown']},0 if inventory['complete'] else 3)
        if args.command == 'resolve':
            return resolve(args.manifest,args.region,args.candidate)
        if args.command in ('check-plan','check-binding'):
            return check_plan(args.manifest,args.plan_json,args.work_dir,args.command=='check-binding')
        return validate(args.manifest,args.plan_json)
    except (json.JSONDecodeError, FileNotFoundError):
        return _emit({'status':'blocked','reason':'missing file or malformed JSON input'},2)
    except InventoryError as error:
        return _emit({'status':'blocked','reason':str(error)},4)
    except (RuntimeError, KeyError, TypeError, ValueError, OSError):
        # Do not expose JSON fragments, signed URLs, or secret filenames.
        return _emit({'status':'blocked','reason':'invalid inputs, incomplete cloud evidence, or operations checkpoint failure'},4)


if __name__ == '__main__':
    raise SystemExit(main())
