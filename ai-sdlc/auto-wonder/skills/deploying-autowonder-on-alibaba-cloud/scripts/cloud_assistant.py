#!/usr/bin/env python3
"""Shared, side-effect-free Cloud Assistant response and recovery contract.

Platform callers own atomic manifest writes/checkpoints and remote submission.
This module never executes or retries a cloud operation and never logs payloads.
"""
import argparse
import base64
import binascii
import json
from pathlib import Path
import sys


TERMINAL_FAILURE = {'Failed', 'PartialFailed', 'Stopped', 'Stopping', 'TimedOut',
                    'Cancelled', 'Invalid', 'Aborted', 'Terminated'}


def first_field(value, names, default=None):
    if isinstance(value, dict):
        for name in names:
            if value.get(name) is not None:
                return value[name]
        children = value.values()
    elif isinstance(value, list):
        children = value
    else:
        return default
    for child in children:
        result = first_field(child, names)
        if result is not None:
            return result
    return default


def invocation_id(response):
    for name in ('InvokeId', 'InvocationId', 'invokeId', 'invocationId'):
        value = response.get(name)
        if isinstance(value, str) and value:
            return value
    raise ValueError('Cloud Assistant invocation ID is missing')


def status(response):
    return first_field(response, ('InvokeRecordStatus', 'InvocationStatus', 'Status'), 'Pending')


def exit_code(response):
    value = first_field(response, ('ExitCode',), -1)
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        return -1
    try:
        return int(value)
    except ValueError:
        return -1


def poll_result(response):
    current, code = status(response), exit_code(response)
    state = 'pending'
    if current in TERMINAL_FAILURE or (current in ('Finished', 'Success') and code != 0):
        state = 'failure'
    elif current in ('Finished', 'Success') and code == 0:
        state = 'success'
    result = {'status': current, 'exitCode': code, 'state': state}
    if state == 'success':
        encoded = first_field(response, ('Output',), '')
        try:
            result['output'] = base64.b64decode(encoded, validate=True).decode('utf-8').strip()
        except (ValueError, TypeError, binascii.Error, UnicodeError):
            raise ValueError('Cloud Assistant returned invalid encoded output') from None
    return result


def assert_settled(document):
    unresolved = 'Previous remote operation is unresolved; reconcile before retrying'
    if (document.get('operationsMigration') or {}).get('status') == 'unknown':
        raise ValueError(unresolved)
    # Preserve the unbound historical manifest contract; bound operations fail closed.
    if document.get('operationsStore') is None:
        return
    if ((document.get('remoteSubmission') or {}).get('status') == 'unknown'
            or (document.get('terraform') or {}).get('pendingOperation')
            or (document.get('status') == 'unknown'
                and document.get('phase') in ('infrastructure', 'terraform-destroy'))):
        raise ValueError(unresolved)
    upgrade = document.get('upgrade') or {}
    for entries in (document.get('remoteInvocations') or [], upgrade.get('remoteInvocations') or []):
        if not isinstance(entries, list):
            raise ValueError(unresolved)
        if any(not isinstance(entry, dict) or entry.get('status') != 'finished' for entry in entries):
            raise ValueError(unresolved)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('invocation-id', 'status', 'exit-code', 'poll', 'assert-settled'))
    parser.add_argument('--manifest', type=Path)
    args = parser.parse_args()
    try:
        document = json.loads(args.manifest.read_text(encoding='utf-8-sig')) if args.manifest else json.loads(sys.stdin.buffer.read().decode('utf-8-sig'))
        if not isinstance(document, dict):
            raise ValueError('Cloud Assistant document must be an object')
        if args.action == 'assert-settled':
            assert_settled(document)
        elif args.action == 'poll':
            print(json.dumps(poll_result(document), ensure_ascii=True))
        else:
            functions = {'invocation-id': invocation_id, 'status': status, 'exit-code': exit_code}
            print(functions[args.action](document))
    except (OSError, ValueError, TypeError, AttributeError, RecursionError):
        # Neither malformed JSON nor cloud output may appear in error diagnostics.
        message = ('Previous remote operation is unresolved; reconcile before retrying'
                   if args.action == 'assert-settled' else 'Cloud Assistant response is invalid')
        print(message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
