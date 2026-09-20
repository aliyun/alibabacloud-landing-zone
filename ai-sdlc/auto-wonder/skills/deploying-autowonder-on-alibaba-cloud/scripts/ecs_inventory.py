#!/usr/bin/env python3
"""Shared, fail-closed DescribeInstances parsing for native upgrade adapters."""
import argparse
import json
import sys

PAGE_SIZE = 100
MAX_PAGES = 1000


def field(value, upper, lower):
    if upper in value and lower in value:
        raise ValueError('ambiguous ECS response fields')
    return value.get(upper, value.get(lower))


def instances(response):
    if not isinstance(response, dict):
        raise ValueError('unrecognized ECS response shape')
    if 'Instances' in response and 'instances' not in response:
        wrapper = response['Instances']
        if not isinstance(wrapper, dict) or 'Instance' not in wrapper:
            raise ValueError('unrecognized ECS instance list shape')
        rows = wrapper['Instance']
    elif 'instances' in response and 'Instances' not in response:
        rows = response['instances']
    else:
        raise ValueError('unrecognized ECS instance list shape')
    if not isinstance(rows, list):
        raise ValueError('ECS instances must be a list')
    result = []
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('malformed ECS instance row')
        instance_id = field(row, 'InstanceId', 'instanceId')
        if not isinstance(instance_id, str) or not instance_id or instance_id.strip() != instance_id:
            raise ValueError('malformed ECS instance identity')
        if instance_id in seen:
            raise ValueError('duplicate ECS instance identity')
        seen.add(instance_id)
        normalized = dict(row)
        normalized.pop('instanceId', None)
        normalized['InstanceId'] = instance_id
        result.append(normalized)
    return result


def integer(value, name):
    if type(value) is not int or value < 0:
        raise ValueError('invalid ECS ' + name)
    return value


def page(response, state=None):
    rows = instances(response)
    state = state or {'ids': [], 'total': None, 'pages': 0, 'done': False}
    if state['done']:
        raise ValueError('ECS pagination already complete')
    number = state['pages'] + 1
    if number > MAX_PAGES:
        raise ValueError('ECS pagination page limit exceeded')
    for upper, lower, expected in (('PageNumber', 'pageNumber', number), ('PageSize', 'pageSize', PAGE_SIZE)):
        value = field(response, upper, lower)
        if upper in response or lower in response:
            if integer(value, upper) != expected:
                raise ValueError('ECS page metadata mismatch')
    token = field(response, 'NextToken', 'nextToken')
    if token not in (None, ''):
        raise ValueError('unsupported ECS pagination token')
    total = field(response, 'TotalCount', 'totalCount')
    if 'TotalCount' in response or 'totalCount' in response:
        total = integer(total, 'total count')
    if state['pages'] and total != state['total']:
        raise ValueError('ECS total count changed between pages')
    if len(rows) > PAGE_SIZE:
        raise ValueError('ECS response exceeds requested page size')
    ids = [row['InstanceId'] for row in rows]
    if set(ids).intersection(state['ids']):
        raise ValueError('duplicate ECS instance across pages; pagination made no progress')
    ids = state['ids'] + ids
    if total is not None:
        if len(ids) > total or (len(rows) < PAGE_SIZE and len(ids) != total):
            raise ValueError('ECS total count does not match retrieved inventory')
        done = len(ids) == total
    else:
        # A full page without TotalCount is never evidence of completeness.
        done = len(rows) < PAGE_SIZE
    if not done and number >= MAX_PAGES:
        raise ValueError('ECS pagination page limit exceeded')
    return {'ids': ids, 'total': total, 'pages': number, 'done': done}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('page', 'target'))
    parser.add_argument('--instance-id')
    args = parser.parse_args()
    try:
        payload = json.loads(sys.stdin.buffer.read().decode('utf-8'))
        if args.action == 'page':
            result = page(payload['response'], payload.get('state'))
        else:
            rows = instances(payload)
            if len(rows) != 1 or rows[0]['InstanceId'] != args.instance_id:
                raise ValueError('ECS target identity mismatch')
            result = rows[0]
        print(json.dumps(result, separators=(',', ':')))
    except (ValueError, TypeError, KeyError) as exc:
        # Only locally authored diagnostics; no cloud response or row data is echoed.
        message = str(exc) if type(exc) is ValueError else 'invalid ECS inventory input'
        print(message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
