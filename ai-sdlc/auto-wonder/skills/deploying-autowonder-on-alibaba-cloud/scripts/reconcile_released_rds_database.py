#!/usr/bin/env python3
"""Remove only the database state orphan of an explicitly confirmed, released RDS."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from operations_bundle import private_write
from operations_hooks import assert_current, checkpoint
from teardown_plan import binding

ADDRESS = 'alicloud_db_database.app'
RECORD = 'releasedRdsDatabaseStateRepair'


class ReconcileError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ReconcileError(message)


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def run(args):
    try:
        return subprocess.run(args, capture_output=True, timeout=180, check=False)
    except (OSError, subprocess.SubprocessError):
        raise ReconcileError('Command result is unknown; preserve the reconciliation checkpoint') from None


def load(path):
    return json.loads(Path(path).read_bytes())


def released_not_found(error):
    # Depending on the Alibaba CLI version, a released parent surfaces either as
    # a single structured JSON error object on stderr (current CLI) or as the
    # legacy SDK.ServerError text block. Both must be unambiguous: an arbitrary
    # NotFound substring, duplicate error-code line, successful empty response,
    # or timeout is never evidence that this specific parent has been released.
    text = error.strip()
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        payload = None
    if isinstance(payload, dict):
        return (payload.get('error_code') == 'InvalidDBInstanceName.NotFound'
                and payload.get('status_code') == 400)
    return (re.findall(r'^ERROR:\s*SDK\.ServerError\s*$', error, re.MULTILINE) == ['ERROR: SDK.ServerError']
            and re.findall(r'^ErrorCode:\s*([^\r\n]+)', error, re.MULTILINE) == ['InvalidDBInstanceName.NotFound'])


def verify_cloud(data):
    profile = ['--profile', 'auto-wonder', '--region', data['region']]
    identity = run(['aliyun', 'sts', 'GetCallerIdentity', *profile])
    require(identity.returncode == 0 and json.loads(identity.stdout).get('AccountId') == data['accountUid'],
            'Authenticated account does not match the deployment')
    result = run(['aliyun', 'rds', 'DescribeDBInstanceAttribute', '--DBInstanceId', data['resources']['rds']['instance_id'], *profile])
    error = result.stderr.decode('utf-8', errors='replace')
    require(result.returncode != 0 and not result.stdout.strip() and released_not_found(error),
            'The exact RDS instance is not confirmed absent')


def target(state, rds):
    require(state.get('version') == 4 and isinstance(state.get('resources'), list)
            and isinstance(state.get('lineage'), str) and bool(state['lineage'])
            and type(state.get('serial')) is int, 'Terraform state structure is unsupported')
    found = [resource for resource in state['resources']
             if resource.get('type') == 'alicloud_db_database' and resource.get('name') == 'app' and not resource.get('module')]
    require(len(found) <= 1, 'Database state address is ambiguous')
    if not found:
        return None
    resource = found[0]
    instances = resource.get('instances')
    require(resource.get('mode') == 'managed' and isinstance(instances, list) and len(instances) == 1
            and 'index_key' not in instances[0] and not instances[0].get('deposed'), 'Database state is not the single fixed resource')
    attrs = instances[0].get('attributes', {})
    require(attrs.get('instance_id') == rds['instance_id'] and attrs.get('data_base_name') == rds['database']
            and attrs.get('id') == rds['instance_id'] + ':' + rds['database'],
            'Database state identity differs from the deployment manifest')
    return resource


def comparable(state):
    # Terraform reconstructs check_results from an unordered map, so its element
    # order varies between reads even when serial is unchanged. It is ephemeral
    # validation output, not resource state; exclude it from equality so a stable
    # deployment is never misread as a concurrent change.
    result = copy.deepcopy(state)
    result.pop('check_results', None)
    return result


def state_without_target(state):
    result = comparable(state)
    result.pop('serial', None)
    result.pop('terraform_version', None)
    result['resources'] = sorted((resource for resource in result['resources']
                                  if not (resource.get('type') == 'alicloud_db_database' and resource.get('name') == 'app'
                                          and not resource.get('module'))), key=lambda resource: canonical(resource))
    return result


def finish_check(before, after, rds):
    require(target(before, rds) is not None and target(after, rds) is None, 'Database state removal is not verified')
    require(after['serial'] > before['serial'] and state_without_target(before) == state_without_target(after),
            'Other Terraform state changed; reconciliation remains pending')


def write_manifest(path, data):
    assert_current(path)
    private_write(Path(path), canonical(data))
    checkpoint(path, data)


def reconcile(manifest_path, confirmation_path):
    manifest_path = Path(manifest_path)
    assert_current(manifest_path)
    data = load(manifest_path)
    require('DESTROY ' + data['deploymentId'] in Path(confirmation_path).read_text().splitlines(),
            'Destructive confirmation does not match this deployment')
    require(data.get('teardownPreparation', {}).get('status') == 'complete', 'Verified teardown preparation is required')
    require(not data.get('terraform', {}).get('pendingOperation'), 'A Terraform operation is unresolved')
    coordinates = binding(data)
    rds = data.get('resources', {}).get('rds', {})
    require(all(isinstance(rds.get(key), str) and rds[key] for key in ('instance_id', 'database')),
            'RDS and database identities are required')
    verify_cloud(data)
    terraform = ['terraform', '-chdir=' + coordinates['workDir']]
    initialized = run(terraform + ['init', '-reconfigure', '-backend-config=' + coordinates['backendFile']])
    require(initialized.returncode == 0, 'Cannot initialize the exact bound state backend')

    def pull():
        result = run(terraform + ['state', 'pull'])
        require(result.returncode == 0, 'Cannot read the exact Terraform state')
        state = json.loads(result.stdout)
        target(state, rds)
        return state

    current = pull()
    resource = target(current, rds)
    record = data.get(RECORD)
    coordinates_record = {'address': ADDRESS, 'accountUid': data['accountUid'], 'region': data['region'],
                          'deploymentId': data['deploymentId'], 'stateBucket': data['terraform']['stateBucket'],
                          'stateKey': data['terraform']['stateKey'], 'rdsInstanceId': rds['instance_id'],
                          'databaseName': rds['database'], 'resourceId': rds['instance_id'] + ':' + rds['database']}
    if record:
        require(record.get('status') in ('pending', 'complete')
                and all(record.get(key) == value for key, value in coordinates_record.items()),
                'Reconciliation checkpoint does not match this deployment')
        backup_path = Path(record['stateFile'])
        require(backup_path.is_absolute() and backup_path.is_file() and not backup_path.is_symlink()
                and backup_path.stat().st_mode & 0o777 == 0o600, 'Protected state backup is unavailable')
        backup = backup_path.read_bytes()
        require(hashlib.sha256(backup).hexdigest() == record.get('stateBackupSha256'), 'State backup checksum mismatch')
        original = json.loads(backup)
        require(original.get('lineage') == record.get('stateLineage') and original.get('serial') == record.get('stateSerial'),
                'State backup metadata differs from the checkpoint')
        # A pending attempt is reconciliation-only: never submit state rm again.
        finish_check(original, current, rds)
        if record['status'] == 'pending':
            record['status'] = 'complete'
            record['resultSerial'] = current['serial']
            write_manifest(manifest_path, data)
        return {'status': 'complete', 'address': ADDRESS}
    if resource is None:
        return {'status': 'not-required', 'address': ADDRESS}
    dry_run = run(terraform + ['state', 'rm', '-dry-run', '-lock=true', '-lock-timeout=60s', ADDRESS])
    require(dry_run.returncode == 0 and dry_run.stdout.decode().strip() == 'Would remove ' + ADDRESS,
            'Dry-run did not select exactly the reviewed database state address')
    require(comparable(pull()) == comparable(current), 'Terraform state changed before reconciliation')
    backup_path = Path(coordinates['workDir']) / 'released-rds-database-state-backup.json'
    require(not backup_path.exists() and not backup_path.is_symlink(), 'An unregistered state backup already exists')
    backup = canonical(current)
    fd = os.open(backup_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(backup)
        stream.flush()
        os.fsync(stream.fileno())
    record = {**coordinates_record, 'status': 'pending', 'stateFile': str(backup_path),
              'stateBackupSha256': hashlib.sha256(backup).hexdigest(), 'stateLineage': current['lineage'],
              'stateSerial': current['serial']}
    data[RECORD] = record
    write_manifest(manifest_path, data)
    # The cloud checkpoint can take time. Reconfirm parent absence and state
    # immediately before the one normal, backend-locked state operation.
    verify_cloud(data)
    require(comparable(pull()) == comparable(current), 'Terraform state changed; pending reconciliation retained')
    removed = run(terraform + ['state', 'rm', '-lock=true', '-lock-timeout=60s', ADDRESS])
    require(removed.returncode == 0, 'State removal result is unknown; pending reconciliation retained')
    after = pull()
    finish_check(current, after, rds)
    record['status'] = 'complete'
    record['resultSerial'] = after['serial']
    write_manifest(manifest_path, data)
    return {'status': 'complete', 'address': ADDRESS}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--confirmation-file', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(reconcile(args.manifest, args.confirmation_file)))
        return 0
    except ReconcileError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        # CLI errors and Terraform state can contain live credentials.
        print('Released RDS database state reconciliation blocked; preserve backups and any pending checkpoint', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
