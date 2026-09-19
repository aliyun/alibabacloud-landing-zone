"""Allowlisted cloud failure metadata; raw CLI diagnostics never leave this boundary."""
import json
import re
import subprocess
import sys

CODES = {
    'InvalidSecurityToken.Expired': 'credential', 'SecurityToken.Expired': 'credential',
    'InvalidSecurityToken.Malformed': 'credential', 'InvalidAccessKeyId.NotFound': 'credential',
    'InvalidAccessKeyId': 'credential', 'InvalidSecurityToken': 'credential',
    'Forbidden': 'permission', 'Forbidden.RAM': 'permission',
    'Forbidden.NoPermission': 'permission', 'AccessDenied': 'permission',
    'UnauthorizedOperation': 'permission',
    'Throttling': 'transient', 'Throttling.User': 'transient',
    'Throttling.Api': 'transient', 'ServiceUnavailable': 'transient',
    'InternalError': 'transient', 'RequestTimeout': 'transient',
    'InvalidInstanceId.NotFound': 'resource', 'ResourceNotFound': 'resource',
    'InvalidRegionId.NotFound': 'configuration',
}


def classify(stdout='', stderr=''):
    result = {'category': 'unknown'}
    for text in (stderr, stdout):
        if not isinstance(text, str):
            continue
        try:
            value = json.loads(text)
        except ValueError:
            value = {}
        if not isinstance(value, dict):
            value = {}
        codes = [value.get('Code'), value.get('ErrorCode')]
        codes += re.findall(r'(?m)^\s*(?:ErrorCode|Error Code|Code)\s*[:=]\s*([A-Za-z0-9.]+)\s*$', text)
        codes += re.findall(r'<Code>([A-Za-z0-9.]+)</Code>', text)
        for code in codes:
            if isinstance(code, str) and code in CODES:
                metadata = {'category': CODES[code], 'code': code}
                request_id = value.get('RequestId')
                if isinstance(request_id, str) and re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', request_id):
                    metadata['requestId'] = request_id
                return metadata
        # Network text affects only the category; never echo any part of it.
        if re.search(r'(?i)\b(?:no such host|connection refused|connection reset|timed out|timeout|TLS handshake timeout)\b', text):
            result = {'category': 'transient'}
    return result


def describe(metadata):
    return '; '.join(key + '=' + metadata[key] for key in ('category', 'code', 'requestId') if key in metadata)


def main():
    if sys.argv[1:] == ['classify']:
        value = json.load(sys.stdin)
        print(json.dumps(classify(value.get('stdout', ''), value.get('stderr', ''))))
        return 0
    try:
        result = subprocess.run(['aliyun', *sys.argv[1:]], capture_output=True, timeout=180)
    except subprocess.TimeoutExpired:
        print('ERROR: Alibaba Cloud API request failed (category=transient; code=RequestTimeout)', file=sys.stderr)
        return 124
    except OSError:
        print('ERROR: Alibaba Cloud API request failed (category=local)', file=sys.stderr)
        return 127
    if result.returncode == 0:
        sys.stdout.buffer.write(result.stdout)
    else:
        metadata = classify(result.stdout.decode('utf-8', 'replace'), result.stderr.decode('utf-8', 'replace'))
        print('ERROR: Alibaba Cloud API request failed (exit %s; %s)' % (result.returncode, describe(metadata)), file=sys.stderr)
    return result.returncode if result.returncode >= 0 else 128 - result.returncode


if __name__ == '__main__':
    sys.exit(main())
