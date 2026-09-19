#!/usr/bin/env python3
"""Fail-closed review of this module's narrowly scoped teardown preparation."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


class ReviewError(RuntimeError):
    pass


STATIC_ADDRESSES = set('''alicloud_vpc.main alicloud_vswitch.zone_a alicloud_vswitch.zone_b
alicloud_security_group.app alicloud_security_group_rule.vpc_internal alicloud_security_group_rule.alb_service
alicloud_alb_load_balancer.app alicloud_alb_server_group.app alicloud_alb_listener.application
alicloud_alb_acl.public_sources alicloud_alb_listener_acl_attachment.public_sources
alicloud_db_instance.main alicloud_db_backup_policy.main alicloud_rds_account.app alicloud_db_database.app alicloud_db_account_privilege.app
alicloud_kvstore_instance.main alicloud_oss_bucket.package alicloud_oss_bucket.artifact
alicloud_log_project.main alicloud_log_store.system alicloud_log_store.business alicloud_log_store.metrics
alicloud_log_store_index.system alicloud_log_store_index.business alicloud_ram_user.app alicloud_ram_policy.app
alicloud_ram_user_policy_attachment.app alicloud_ram_access_key.app'''.split())
TAGGED_TYPES = {'alicloud_vpc', 'alicloud_vswitch', 'alicloud_security_group', 'alicloud_instance',
                'alicloud_alb_load_balancer', 'alicloud_alb_server_group', 'alicloud_alb_listener',
                'alicloud_alb_acl', 'alicloud_db_instance', 'alicloud_kvstore_instance',
                'alicloud_oss_bucket', 'alicloud_log_project', 'alicloud_ram_policy'}
TARGETS = {
    'alicloud_db_instance.main': {'deletion_protection': False},
    'alicloud_db_backup_policy.main': {'backup_retention_period': 7, 'log_backup_retention_period': 7, 'released_keep_policy': 'None'},
    'alicloud_kvstore_instance.main': {'instance_release_protection': False},
    **{'alicloud_oss_bucket.' + name: {'force_destroy': True, 'versioning': [{'status': 'Suspended'}]}
       for name in ('package', 'artifact')},
    **{'alicloud_log_store.' + name: {'retention_period': 7} for name in ('system', 'business', 'metrics')},
}


def require(condition, message):
    if not condition:
        raise ReviewError(message)


def required(value):
    require(isinstance(value, str) and bool(value), 'Required deployment identity is missing')
    return value


def unknown(value):
    if isinstance(value, dict):
        return any(unknown(item) for item in value.values())
    if isinstance(value, list):
        return any(unknown(item) for item in value)
    return value is True


def review(manifest, plan):
    resources = manifest['resources']
    tags = {'Project': 'AutoWonder', 'Environment': required(manifest.get('environment')),
            'DeploymentId': required(manifest.get('deploymentId')), 'ManagedBy': 'Terraform', 'Topology': 'multi-az-ha'}
    ecs = resources['ecs_instance_ids']
    require(set(ecs) == {'zone_a', 'zone_b'} and len(set(ecs.values())) == 2, 'Exact two-node deployment inventory is required')
    expected = STATIC_ADDRESSES | {'alicloud_instance.app[' + json.dumps(zone) + ']' for zone in ecs}
    cidrs = manifest['publicSourceCidrs']
    require(isinstance(cidrs, list) and bool(cidrs) and all(isinstance(cidr, str) for cidr in cidrs), 'Public source inventory is missing')
    expected |= {'alicloud_alb_acl_entry_attachment.public_sources[' + json.dumps(cidr) + ']' for cidr in cidrs}
    changes = plan.get('resource_changes')
    require(isinstance(changes, list), 'Terraform resource changes are missing')
    addresses = [item.get('address') for item in changes]
    require(len(addresses) == len(set(addresses)) and set(TARGETS) <= set(addresses) <= expected,
            'Terraform plan must contain all teardown targets and only known module dependencies')
    identities = {
        'alicloud_db_instance.main': {'id': required(resources['rds'].get('instance_id'))},
        'alicloud_db_backup_policy.main': {'instance_id': required(resources['rds'].get('instance_id'))},
        'alicloud_kvstore_instance.main': {'id': required(resources['redis'].get('instance_id'))},
        'alicloud_alb_load_balancer.app': {'id': required(resources.get('load_balancer_id'))},
        'alicloud_log_project.main': {'project_name': required(resources['sls'].get('project'))},
    }
    for zone, instance in ecs.items():
        identities['alicloud_instance.app[' + json.dumps(zone) + ']'] = {'id': required(instance)}
    for kind in ('package', 'artifact'):
        bucket = required(resources.get(kind + '_bucket') or resources.get('oss', {}).get(kind + '_bucket'))
        identities['alicloud_oss_bucket.' + kind] = {'bucket': bucket, 'id': bucket}
    for name in ('system', 'business', 'metrics'):
        identities['alicloud_log_store.' + name] = {
            'project_name': required(resources['sls'].get('project')),
            'logstore_name': required(resources['sls']['stores'].get(name))}
    count = 0
    for item in changes:
        address, change = item['address'], item['change']
        require(item.get('mode') == 'managed', 'Only existing managed resources are allowed')
        actions = change.get('actions')
        require(actions in (['no-op'], ['update']), 'Create, delete, replace and read actions are forbidden')
        before, after = change.get('before'), change.get('after')
        require(isinstance(before, dict) and isinstance(after, dict) and bool(before.get('id')), 'Resource state is incomplete')
        for value in (before, after):
            for field, identity in identities.get(address, {}).items():
                require(value.get(field) == identity, 'Resource identity differs from the deployment inventory')
            if address.split('.', 1)[0] in TAGGED_TYPES:
                require(isinstance(value.get('tags'), dict), 'Resource ownership tags are missing')
            if 'tags' in value:
                require(isinstance(value['tags'], dict) and all(value['tags'].get(key) == expected_value for key, expected_value in tags.items()),
                        'Resource ownership tags differ from the deployment')
        targets = TARGETS.get(address, {})
        for key, target in targets.items():
            require(type(after.get(key)) is type(target) and after.get(key) == target, 'Teardown protection target is not satisfied')
        if actions == ['no-op']:
            continue
        require(address in TARGETS and not unknown(change.get('after_unknown', {})), 'Unreviewed or unknown resource mutation')
        changed = {key for key in before.keys() | after.keys() if before.get(key) != after.get(key)}
        require(bool(changed) and changed <= targets.keys(), 'Mutation changes more than teardown protections and retention')
        count += 1
    return {'status': 'approved', 'updatedResources': count, 'resourceCount': len(changes)}


def post_review(manifest, plan, reviewed_plan):
    """Check refreshed actual values, permitting one unchanged backup-retention floor."""
    review(manifest, reviewed_plan)
    reviewed = {item['address']: item['change'] for item in reviewed_plan['resource_changes']}
    summary = review(manifest, plan)
    retained_days = None
    backup_address = 'alicloud_db_backup_policy.main'
    for item in plan['resource_changes']:
        address, change = item['address'], item['change']
        before, after = change['before'], change['after']
        changed = {key for key in before.keys() | after.keys() if before.get(key) != after.get(key)}
        for field, target in TARGETS.get(address, {}).items():
            actual = before.get(field)
            if type(actual) is type(target) and actual == target:
                continue
            # Some RDS configurations retain their original backup retention even
            # after accepting a shorter value. This is not a deletion protection.
            # Never generalize the exception to a new value or another attribute.
            require(address == backup_address and field == 'backup_retention_period'
                    and type(actual) is int and actual > target
                    and actual == reviewed[address]['before'].get(field)
                    and reviewed[address]['after'].get(field) == target
                    and change['actions'] == ['update'] and changed == {field},
                    'Actual teardown protections differ from the reviewed postcondition')
            retained_days = actual
        if change['actions'] == ['update']:
            require(address == backup_address and changed == {'backup_retention_period'} and retained_days is not None,
                    'An unreviewed update remains after teardown preparation')
    result = {'status': 'verified', 'remainingPlanUpdates': summary['updatedResources'],
              'resourceCount': summary['resourceCount']}
    if retained_days is not None:
        result['retainedBackupRetentionDays'] = retained_days
        result['plannedBackupRetentionDays'] = TARGETS[backup_address]['backup_retention_period']
    return result


def binding(manifest):
    account = required(manifest.get('accountUid'))
    region = required(manifest.get('region'))
    deployment = required(manifest.get('deploymentId'))
    require(bool(re.fullmatch(r'[0-9]{15,20}', account)) and bool(re.fullmatch(r'[a-z0-9][a-z0-9-]{5,31}', deployment)), 'Invalid deployment identity')
    terraform = manifest['terraform']
    directory = Path(required(manifest['localContext'].get('terraformDirectory')))
    backend_dir = Path(required(terraform.get('backendDirectory')))
    backend = Path(required(terraform.get('stateReference')))
    require(directory.is_absolute() and directory.is_dir() and backend == backend_dir / 'backend.hcl' and backend.is_file(), 'Terraform paths are not bound to the deployment')
    require(manifest.get('stateMode') == 'remote' and terraform.get('backendStatus') == 'ready', 'A ready remote backend is required')
    digest = hashlib.sha256((account + '|' + region + '|' + deployment).encode()).hexdigest()[:12]
    bucket = 'aw-tfstate-' + deployment + '-' + digest
    key = 'states/' + deployment + '/terraform.tfstate'
    require(terraform.get('stateBucket') == bucket and terraform.get('stateKey') == key, 'State backend metadata differs from deployment identity')
    text = backend.read_text()
    for name, value in {'bucket': bucket, 'key': key, 'region': region, 'endpoint': 'oss-' + region + '.aliyuncs.com'}.items():
        values = re.findall(r'(?m)^\s*' + name + r'\s*=\s*"([^"\n]+)"\s*$', text)
        require(values == [value], 'Backend file differs from the manifest binding')
    return {'workDir': str(directory.resolve()), 'backendFile': str(backend.resolve())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('binding', 'review', 'targets', 'post'))
    parser.add_argument('--manifest')
    parser.add_argument('--plan-json')
    parser.add_argument('--reviewed-plan-json')
    args = parser.parse_args()
    try:
        if args.command == 'targets':
            print(json.dumps(sorted(TARGETS)))
            return 0
        require(bool(args.manifest), 'A manifest is required')
        manifest = json.loads(Path(args.manifest).read_text())
        if args.command == 'binding':
            result = binding(manifest)
        elif args.command == 'post':
            result = post_review(manifest, json.loads(Path(args.plan_json).read_text()),
                                 json.loads(Path(args.reviewed_plan_json).read_text()))
        else:
            result = review(manifest, json.loads(Path(args.plan_json).read_text()))
        print(json.dumps(result))
        return 0
    except (ReviewError, OSError, ValueError, KeyError, TypeError, AttributeError):
        # Plan data can include credentials. Never echo values or raw exception text.
        print('Teardown preparation review blocked; inspect protected local inputs', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
