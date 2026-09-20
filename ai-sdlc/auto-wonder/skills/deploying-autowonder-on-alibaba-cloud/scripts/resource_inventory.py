"""Read-only Alibaba Cloud discovery. Unknown responses are never empty stock.

Only the CLI boundary knows provider response shapes. All callers receive
normalized facts; nothing here creates cloud resources or writes a manifest.
"""
import copy
from datetime import datetime, timezone
import json
import math
import random
import re
import subprocess
import time


class InventoryError(ValueError):
    pass


# Discovery is serial. Share pacing across all parameters/regions of an API in
# this process; separate deployment processes still share the cloud account quota.
REQUEST_INTERVAL_SECONDS = 0.3
_request_completed_at = {}


def request(region, service, action, params):
    args = ['aliyun', service, action, '--region', region, '--profile', 'auto-wonder']
    for key, value in params.items():
        if value is not None:
            args += ['--' + key, str(value).lower() if isinstance(value, bool) else str(value)]
    api = (service, action)
    for attempt in range(3):
        completed = _request_completed_at.get(api)
        if completed is not None:
            remaining = REQUEST_INTERVAL_SECONDS - (time.monotonic() - completed)
            if remaining > 0:
                time.sleep(remaining)
        category = 'api-error'
        throttled = False
        try:
            try:
                result = subprocess.run(args, capture_output=True, text=True, timeout=30)
            finally:
                # Failed calls and timeouts consume quota too. Retry backoff counts
                # toward the gap instead of adding another unconditional sleep.
                _request_completed_at[api] = time.monotonic()
            if result.returncode == 0:
                try:
                    data = json.loads(result.stdout)
                except json.JSONDecodeError:
                    raise InventoryError('invalid-json') from None
                if not isinstance(data, dict) or data.get('Code') or data.get('Success') in (False, 'false'):
                    raise InventoryError('invalid-response')
                return data
            # Inspect diagnostics only in memory; never echo signed requests.
            diagnostic = (result.stderr + result.stdout).lower()
            throttled = 'throttl' in diagnostic
            if any(word in diagnostic for word in ('forbidden', 'accessdenied', 'unauthorized')):
                category = 'permission'
            elif any(word in diagnostic for word in ('throttl', 'timeout', 'temporarily', 'internalerror', 'serviceunavailable')):
                category = 'transient'
            elif any(word in diagnostic for word in ('unsupportedclasscode', 'invalidclasscode.notfound', 'invalidinstancetype.notsupported', 'invalidinstancetype.valuenotsupported', 'operationdenied.zoneresource', 'soldout', 'resourceinsufficient')):
                category = 'unavailable'
            elif any(word in diagnostic for word in ('insufficientbalance', 'nocredit', 'quota')):
                category = 'account-limit'
        except subprocess.TimeoutExpired:
            category = 'timeout'
        except OSError:
            raise InventoryError('cli-unavailable') from None
        if category not in ('transient', 'timeout') or attempt == 2:
            raise InventoryError(category)
        time.sleep(2 ** attempt + (random.uniform(0, REQUEST_INTERVAL_SECONDS) if throttled else 0))


def rows(data, key, wrapper=None):
    if not isinstance(data, dict) or key not in data:
        raise InventoryError('missing-field:' + key)
    value = data[key]
    if isinstance(value, dict) and wrapper:
        value = value.get(wrapper)
    if not isinstance(value, list) or not all(isinstance(row, dict) for row in value):
        raise InventoryError('invalid-list:' + key)
    return value


def pages(region, service, action, params, path, *, numbered=False, evidence=None, deadline=None):
    """Drain token or numbered pagination. Repeated/truncated pages fail closed."""
    params = dict(params)
    result, seen = [], set()
    deadline = deadline or time.monotonic() + 600
    if numbered:
        params.update(PageSize=100, PageNumber=1)
    else:
        params['MaxResults'] = 100
    while True:
        if time.monotonic() > deadline:
            raise InventoryError('discovery-time-budget')
        data = request(region, service, action, params)
        page = data
        for key in path:
            if not isinstance(page, dict) or key not in page:
                raise InventoryError('missing-page-field')
            page = page[key]
        if not isinstance(page, list):
            raise InventoryError('invalid-page')
        if evidence is not None:
            evidence.append(data.get('RequestId', ''))
        result.extend(page)
        if numbered:
            total = data.get('TotalCount')
            if not isinstance(total, int) or total < len(result):
                raise InventoryError('invalid-page-total')
            if len(result) == total:
                return result
            if not page:
                raise InventoryError('incomplete-pages')
            marker = json.dumps(page, sort_keys=True)
            params['PageNumber'] += 1
        else:
            marker = data.get('NextToken')
            if not marker:
                return result
            if not isinstance(marker, str):
                raise InventoryError('invalid-page-token')
            params['NextToken'] = marker
        if marker in seen:
            raise InventoryError('repeated-page')
        seen.add(marker)


def price(data, service=None):
    if service == "redis":
        order = data.get("Order", {})
        if not isinstance(order, dict): raise InventoryError('unknown-price')
        def amount(key):
            value = order.get(key)
            if isinstance(value, bool): return None
            try: return float(value)
            except (TypeError, ValueError): return None
        data = {"PriceInfo":{"Currency":order.get("Currency"),"OriginalPrice":amount("OriginalAmount"),"TradePrice":amount("TradeAmount")}}
    info = data.get('PriceInfo', {})
    if not isinstance(info, dict): raise InventoryError('unknown-price')
    info = info.get('Price', info)
    if not isinstance(info, dict): raise InventoryError('unknown-price')
    amount = info.get('OriginalPrice')
    trade = info.get('TradePrice')
    currency = info.get('Currency')
    if (currency not in ('CNY', 'USD') or isinstance(amount, bool)
            or not isinstance(amount, (int, float)) or not math.isfinite(amount) or amount < 0):
        raise InventoryError('unknown-price')
    return {'amount': amount, 'currency': currency,
            'tradeAmount': trade if isinstance(trade, (int, float)) and math.isfinite(trade) and trade >= 0 else None,
            'periodMonths': 1, 'basis': 'original-purchase-price', 'renewal': 'not-quoted'}


def stock(data, wanted, disk_size=None):
    zones = []
    for zone in rows(data, 'AvailableZones', 'AvailableZone'):
        zone_id = zone.get('ZoneId')
        if not isinstance(zone_id, str):
            raise InventoryError('missing-zone-id')
        for resource in rows(zone, 'AvailableResources', 'AvailableResource'):
            for item in rows(resource, 'SupportedResources', 'SupportedResource'):
                if item.get('Value') == wanted:
                    if disk_size is not None:
                        low, high = item.get('Min'), item.get('Max')
                        if (not all(type(n) is int for n in (low, high))
                                or item.get('Unit') != 'GiB' or 'Status' not in item):
                            raise InventoryError('unknown-disk-support')
                        if (item['Status'] == 'Available' and low <= disk_size <= high
                                and item.get('StatusCategory') in (None, 'WithStock', 'ClosedWithStock')):
                            zones.append(zone_id)
                        continue
                    if 'Status' not in item or 'StatusCategory' not in item:
                        raise InventoryError('missing-stock-status')
                    if item['Status'] == 'Available' and item['StatusCategory'] in ('WithStock', 'ClosedWithStock'):
                        zones.append(zone_id)
    return sorted(set(zones))


def redis_classes(data, policy, product='Local', excluded=None):
    result = []
    excluded = excluded if excluded is not None else []
    def walk(node, levels, context):
        if not levels:
            for item in rows(node, 'AvailableResources', 'AvailableResource'):
                capacity = item.get('Capacity')
                if type(capacity) is not int or capacity <= 0 or not item.get('InstanceClass'):
                    excluded.append({'service':'redis', 'type':item.get('InstanceClass'), 'reason':'unknown-redis-capacity'})
                    continue
                if capacity == policy['capacityMb']:
                    result.append(dict(context, type=item['InstanceClass'], capacityMb=capacity))
            return
        key, wrapper, field, expected = levels[0]
        for child in rows(node, key, wrapper):
            # OnECS omits shard count for its explicitly non-cluster branch.
            if product == 'OnECS' and field == 'ShardNumber' and field not in child:
                walk(child, levels[1:], context)
                continue
            if field not in child:
                raise InventoryError('missing-redis-' + field)
            actual = str(child[field])
            if field == 'Engine': actual, expected = actual.lower(), expected.lower()
            if product == 'OnECS' and field == 'Architecture' and actual == 'non_cluster': actual = 'standard'
            # Numeric OnECS enums are not interpreted as a replica count.
            # Exact MASTER_SLAVE pair quotes and explicit create params bind HA.
            if product == 'OnECS' and field == 'SupportedNodeType' and actual.isdigit():
                walk(child, levels[1:], context)
            elif expected is None or actual == expected:
                walk(child, levels[1:], context)
    for zone in rows(data, 'AvailableZones', 'AvailableZone'):
        if not zone.get('ZoneId'):
            raise InventoryError('missing-redis-zone')
        walk(zone, [
            ('SupportedEngines', 'SupportedEngine', 'Engine', policy['engine']),
            ('SupportedEditionTypes', 'SupportedEditionType', 'EditionType', policy['edition']),
            ('SupportedSeriesTypes', 'SupportedSeriesType', 'SeriesType', None),
            ('SupportedEngineVersions', 'SupportedEngineVersion', 'Version', policy['version']),
            ('SupportedArchitectureTypes', 'SupportedArchitectureType', 'Architecture', policy['architecture']),
            ('SupportedShardNumbers', 'SupportedShardNumber', 'ShardNumber', '1'),
            ('SupportedNodeTypes', 'SupportedNodeType', 'SupportedNodeType', policy['nodeType']),
        ], {'zone': zone['ZoneId']})
    return list({(r['zone'],r['type'],r['capacityMb']):r for r in result}.values())


def collect_inventory(manifest, policy):
    """Collect facts for the template's product shapes, not guessed SKU prefixes.

The catalogues/quotes do not reserve inventory. Pair support is checked using
both zones' offers and explicit secondary-zone parameters where APIs support
those parameters. Order-time account/quota checks remain execution limitations.
"""
    region = manifest['region']
    result = {'complete': True, 'region': region, 'ecs': [], 'rds': [], 'redis': [],
              'alb': [], 'images': [], 'unknown': [], 'queriedAt': datetime.now(timezone.utc).isoformat()}
    cache = {}
    deadline = time.monotonic() + 600
    def query(service, action, **params):
        key = (service, action, json.dumps(params, sort_keys=True))
        if time.monotonic() > deadline:
            raise InventoryError('discovery-time-budget')
        if key not in cache:
            value = request(region, service, action, params)
            cache[key] = (value, {'service': service, 'action': action, 'params': params,
                                'queriedAt': datetime.now(timezone.utc).isoformat(),
                                'requestId': value.get('RequestId', ''), 'complete': True})
        return copy.deepcopy(cache[key])
    def unknown(service, error):
        result['complete'] = False
        result['unknown'].append({'service': service, 'reason': str(error)})
    try:
        identity, _ = query('sts', 'GetCallerIdentity')
        if identity.get('AccountId') != manifest.get('accountUid') or not manifest.get('accountUid'):
            raise InventoryError('account-identity-mismatch')
    except InventoryError as error:
        unknown('identity', error)
    selected = manifest.get('resolvedInfrastructure', {})
    refresh = manifest.get('_refreshSelection')
    existing = manifest.get('_existingPlanTypes', [])
    prior = manifest.get('resourceSelection', {}).get('evidence', {})
    if 'alicloud_alb_load_balancer' in existing:
        result['alb'] = copy.deepcopy(prior['alb'])
    else:
        try:
            data, proof = query('alb', 'DescribeZones')
            result['alb'] = [{'zone': z['ZoneId'], 'evidence': proof} for z in rows(data, 'Zones') if z.get('ZoneId')]
            if len(result['alb']) != len(rows(data, 'Zones')):
                raise InventoryError('missing-alb-zone')
        except InventoryError as error:
            unknown('alb', error)
    if 'alicloud_instance' in existing:
        result['ecs'] = copy.deepcopy(prior['ecs'])
        result['images'] = [copy.deepcopy(prior['image'])]
    else:
        try:
            ids = []
            params = {'CpuArchitecture': policy['ecs']['architecture'], 'MinimumCpuCoreCount': policy['ecs']['cpu'],
                      'MaximumCpuCoreCount': policy['ecs']['cpu'], 'MinimumMemorySize': policy['ecs']['memoryGiB'],
                      'MaximumMemorySize': policy['ecs']['memoryGiB'], 'InstanceFamilyLevel': policy['ecs']['familyLevel']}
            if refresh:
                params['InstanceTypes.1'] = selected['ecsInstanceType']
            types = pages(region, 'ecs', 'DescribeInstanceTypes', params, ('InstanceTypes', 'InstanceType'), evidence=ids, deadline=deadline)
            for item in types:
                if not isinstance(item, dict):
                    raise InventoryError('invalid-instance-type')
                if (item.get('CpuCoreCount'), item.get('MemorySize'), item.get('CpuArchitecture')) != (policy['ecs']['cpu'], policy['ecs']['memoryGiB'], policy['ecs']['architecture']):
                    continue
                if 'InstanceFamilyLevel' not in item: raise InventoryError('unknown-ecs-performance-class')
                if item['InstanceFamilyLevel'] != policy['ecs']['familyLevel']: continue
                try:
                    tid = item['InstanceTypeId']
                    data, proof = query('ecs', 'DescribeAvailableResource', RegionId=region, DestinationResource='InstanceType', InstanceChargeType='PrePaid', InstanceType=tid)
                    disks, diskproof = query('ecs', 'DescribeAvailableResource', RegionId=region, DestinationResource='SystemDisk', InstanceChargeType='PrePaid', InstanceType=tid)
                    zones = sorted(set(stock(data, tid)) & set(stock(disks, policy['ecs']['systemDisk'], policy['ecs']['systemDiskGiB'])))
                    if not zones: continue
                    image_params = {'RegionId': region, 'ImageOwnerAlias': 'system', 'Architecture': 'x86_64',
                                    'OSType': 'linux', 'Status': 'Available', 'InstanceType': tid}
                    if selected.get('ecsImageId'): image_params['ImageId'] = selected['ecsImageId']
                    image_ids = []
                    images = pages(region, 'ecs', 'DescribeImages', image_params, ('Images', 'Image'), numbered=True, evidence=image_ids, deadline=deadline)
                    for image in images:
                        if (image.get('Architecture') != 'x86_64' or image.get('OSType') != 'linux'
                                or image.get('Status') != 'Available' or image.get('ImageOwnerAlias') != 'system'): continue
                        if not selected.get('ecsImageId') and not str(image.get('ImageId', '')).startswith(policy['ecs']['defaultImagePrefix']): continue
                        result['images'].append({'id': image['ImageId'], 'instanceType': tid, 'createdAt': image.get('CreationTime', ''),
                                                'evidence': {'service': 'ecs', 'action': 'DescribeImages', 'params': image_params,
                                                             'requestIds': image_ids, 'queriedAt': result['queriedAt'], 'complete': True}})

                    for zone in zones:
                        quote, priceproof = query('ecs', 'DescribePrice', RegionId=region, ResourceType='instance', InstanceType=tid, ZoneId=zone,
                                                  PriceUnit='Month', Period=1, Amount=1, **{'SystemDisk.Category': policy['ecs']['systemDisk'], 'SystemDisk.Size': policy['ecs']['systemDiskGiB']})
                        result['ecs'].append({'zone': zone, 'type': tid, 'cpu': item['CpuCoreCount'], 'memoryGiB': item['MemorySize'],
                                              'architecture': item['CpuArchitecture'], 'familyLevel': item['InstanceFamilyLevel'], 'price': price(quote),
                                              'evidence': [proof, diskproof, priceproof, {'service': 'ecs', 'action': 'DescribeInstanceTypes', 'params': params, 'requestIds': ids, 'complete': True, 'queriedAt': result['queriedAt']}]})
                except InventoryError as error:
                    if str(error) in ('missing-field:AvailableZones', 'missing-stock-status', 'unknown-disk-support'):
                        result.setdefault('excluded', []).append({'service':'ecs','type':tid,'reason':str(error)})
                        continue
                    if str(error) != 'unavailable': raise
                    result.setdefault('unavailable', []).append({'service':'ecs','type':item['InstanceTypeId']})
        except (InventoryError, KeyError, TypeError, AttributeError) as error:
            unknown('ecs', error if isinstance(error, InventoryError) else InventoryError('invalid-instance-response'))
    if 'alicloud_db_instance' in existing:
        result['rds'] = copy.deepcopy(prior['rds'])
    else:
        try:
            rp = policy['rds']
            data, zoneproof = query('rds', 'DescribeAvailableZones', RegionId=region, Engine=rp['engine'], EngineVersion=rp['version'], CommodityCode='rds')
            for zone in rows(data, 'AvailableZones', 'AvailableZone'):
                z = zone['ZoneId']
                for engine in rows(zone, 'SupportedEngines', 'SupportedEngine'):
                    if engine.get('Engine') != rp['engine']: continue
                    for version in rows(engine, 'SupportedEngineVersions', 'SupportedEngineVersion'):
                        if version.get('Version') != rp['version']: continue
                        for category in rows(version, 'SupportedCategorys', 'SupportedCategory'):
                            cat = category['Category']
                            # Existing product shape is an input constraint, not a SKU preference.
                            if cat not in rp['categories'] or cat != (selected.get('rdsCategory') or 'HighAvailability'): continue
                            for storage in rows(category, 'SupportedStorageTypes', 'SupportedStorageType'):
                                st = storage['StorageType']
                                if st not in rp['storageTypes']: continue
                                data, proof = query('rds', 'DescribeAvailableClasses', RegionId=region, ZoneId=z, InstanceChargeType='Prepaid',
                                                    Engine=rp['engine'], EngineVersion=rp['version'], Category=cat, DBInstanceStorageType=st, OrderType='BUY')
                                for cls in rows(data, 'DBInstanceClasses', 'DBInstanceClass'):
                                    tid = cls['DBInstanceClass']
                                    if refresh and tid != selected['rdsInstanceType']: continue
                                    try:
                                        detail, detailproof = query('rds', 'DescribeClassDetails', RegionId=region,
                                                                    CommodityCode='rds', ClassCode=tid, Engine=rp['engine'], EngineVersion=rp['version'])
                                        cpu_text = str(detail.get('Cpu', '')).strip()
                                        if not cpu_text.isdigit():
                                            raise InventoryError('unknown-rds-capacity')
                                        if int(cpu_text) != rp['cpu']: continue
                                        memory = re.fullmatch(r'\s*([0-9]+(?:\.[0-9]+)?)\s*GB\s*(?:（[^（）]*）|\([^()]*\))?\s*', str(detail.get('MemoryClass', '')))
                                        if memory is None:
                                            raise InventoryError('unknown-rds-capacity')
                                        cpu, mem = int(detail['Cpu']), float(memory[1])
                                        if (cpu, mem) != (rp['cpu'], rp['memoryGiB']): continue
                                        bounds = cls['DBInstanceStorageRange']
                                        size = selected.get('rdsStorageGb') or rp['storageGiB']
                                        if size != rp['storageGiB']: raise InventoryError('rds-storage-differs-from-policy')
                                        low, high, step = (bounds[k] for k in ('MinValue', 'MaxValue', 'Step'))
                                        if not all(isinstance(n, int) for n in (low, high, step)) or step <= 0:
                                            raise InventoryError('invalid-rds-storage-range')
                                        if not (low <= size <= high and (size-low) % step == 0): continue
                                        quote, priceproof = query('rds', 'DescribePrice', RegionId=region, Engine=rp['engine'], EngineVersion=rp['version'],
                                                                  DBInstanceClass=tid, DBInstanceStorage=size, DBInstanceStorageType=st, ZoneId=z,
                                                                  PayType='Prepaid', TimeType='Month', UsedTime=1, Quantity=1, CommodityCode='rds', OrderType='BUY')
                                        result['rds'].append({'zone': z, 'type': tid, 'category': cat, 'storageType': st, 'storageGiB': size,
                                                              'cpu': cpu, 'memoryGiB': mem, 'storageRange': bounds, 'price': price(quote), 'evidence': [zoneproof, proof, detailproof, priceproof]})
                                    except InventoryError as error:
                                        if str(error) == 'unknown-rds-capacity':
                                            result.setdefault('excluded', []).append({'service':'rds','type':tid,'reason':str(error)})
                                            continue
                                        if str(error) != 'unavailable': raise
                                        result.setdefault('unavailable', []).append({'service':'rds','type':tid})
        except (InventoryError, KeyError, TypeError, AttributeError) as error:
            unknown('rds', error if isinstance(error, InventoryError) else InventoryError('invalid-rds-response'))
    if 'alicloud_kvstore_instance' in existing:
        result['redis'] = copy.deepcopy(prior['redis'])
    else:
        try:
            for product in ('Local', 'OnECS'):
                data, proof = query('r-kvstore', 'DescribeAvailableResource', RegionId=region, InstanceChargeType='PrePaid', OrderType='BUY', Engine='Redis', ProductType=product)
                for cls in redis_classes(data, policy['redis'], product, result.setdefault('excluded', [])):
                    if refresh and cls['type'] != selected['redisInstanceClass']: continue
                    cls['evidence'] = [proof]
                    if not any(r['zone'] == cls['zone'] and r['type'] == cls['type'] for r in result['redis']):
                        result['redis'].append(cls)
            # Query exact primary/secondary pair quotes, not just an unqualified SKU price.
            for cls in result['redis']:
                cls['pairs'] = {}
                for secondary in sorted({r['zone'] for r in result['redis'] if r['type'] == cls['type']} - {cls['zone']}):
                    try:
                        quote, priceproof = query('r-kvstore', 'DescribePrice', RegionId=region, InstanceClass=cls['type'], ZoneId=cls['zone'],
                                                  SecondaryZoneId=secondary, ChargeType='PrePaid', Period=1, Quantity=1,
                                                  NodeType='MASTER_SLAVE', EngineVersion=policy['redis']['version'], OrderType='BUY')
                        cls['pairs'][secondary] = {'price': price(quote, 'redis'), 'evidence': priceproof}
                    except InventoryError as error:
                        if str(error) != 'unavailable': raise
                        result.setdefault('unavailable', []).append({'service':'redis','type':cls['type'],'primary':cls['zone'],'secondary':secondary})
        except InventoryError as error:
            unknown('redis', error)
    if time.monotonic() > deadline: unknown('inventory', InventoryError('discovery-time-budget'))
    result['limitations'] = ['Inventory is not reserved; final order acceptance and account quota are checked by the provider.',
                             'RDS catalogues validate each HA zone offer; the order validates the final placement.',
                             'Quotes cover core subscription resources; ALB/OSS/SLS usage and renewal charges are not a fixed total.']
    return result
