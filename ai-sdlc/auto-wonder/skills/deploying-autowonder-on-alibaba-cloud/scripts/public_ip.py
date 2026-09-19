#!/usr/bin/env python3
"""Read-only public IPv4 discovery; never writes manifests or firewall rules.

Exit codes: detected/manual=0, invalid input=2, unavailable=5. When automatic
discovery is selected, use every returned publicSourceCidrs entry without confirmation.
"""
import argparse
import ipaddress
import json
import math
import queue
import re
import threading
import time
import urllib.request

SOURCES = {
    'ipip': 'https://myip.ipip.net/',
    'ipify': 'https://api.ipify.org?format=json',
    'myip': 'https://api.myip.com/',
}
# Explicit ranges also make validation stable across Python ipaddress versions.
NON_PUBLIC = tuple(ipaddress.IPv4Network(value) for value in (
    '0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8',
    '169.254.0.0/16', '172.16.0.0/12', '192.0.0.0/24', '192.0.2.0/24',
    '192.88.99.0/24', '192.168.0.0/16', '198.18.0.0/15',
    '198.51.100.0/24', '203.0.113.0/24', '224.0.0.0/4', '240.0.0.0/4',
))


def validate_ipv4(value):
    if not isinstance(value, str):
        raise ValueError('public IPv4 text required')
    address = ipaddress.IPv4Address(value)
    if not address.is_global or any(address in network for network in NON_PUBLIC):
        raise ValueError('public IPv4 required')
    return str(address)


def validate_cidr(value):
    network = ipaddress.IPv4Network(value, strict=True)
    if any(network.overlaps(blocked) for blocked in NON_PUBLIC):
        raise ValueError('CIDR must contain only public IPv4 addresses')
    validate_ipv4(str(network.network_address))
    validate_ipv4(str(network.broadcast_address))
    return str(network)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('redirect refused')


def fetch_source(source, timeout):
    # Preserve the user's network route. Never log proxy URLs or credentials;
    # urllib handles proxy authentication separately from origin authorization.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(), NoRedirect())
    request = urllib.request.Request(SOURCES[source], headers={'Accept': 'application/json, text/plain'})
    with opener.open(request, timeout=timeout) as response:
        body = response.read(4097)
    if len(body) > 4096:
        raise ValueError('response too large')
    return body.decode('utf-8')


def parse_source(source, body):
    if source == 'ipip':
        match = re.fullmatch(r'\s*当前\s*IP[：:]\s*([0-9.]+)\s+来自于[：:][^\r\n]*\s*', body)
        if not match:
            raise ValueError('invalid IPIP response')
        value = match.group(1)
    elif source in ('ipify', 'myip'):
        data = json.loads(body)
        if not isinstance(data, dict) or not isinstance(data.get('ip'), str):
            raise ValueError('invalid JSON response')
        value = data['ip']
    else:
        raise ValueError('unknown source')
    return validate_ipv4(value)


def discover(timeout=6.0, sources=None):
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError('timeout must be positive and finite')
    selected = list(SOURCES) if sources is None else list(dict.fromkeys(sources))
    if not selected or any(source not in SOURCES for source in selected):
        raise ValueError('unknown or empty sources')
    deadline = time.monotonic() + timeout
    results = queue.Queue()

    def worker(source):
        try:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError()
            address = parse_source(source, fetch_source(source, min(3.0, remaining)))
            results.put((source, address, None))
        except Exception as exc:
            # Exception strings may contain proxy URLs, credentials or response data.
            error = 'timeout' if isinstance(exc, TimeoutError) else 'request_or_response_failed'
            results.put((source, None, error))

    # At most three fixed services. Daemon workers are intentional: DNS/read can
    # outlive socket timeouts; neither discover nor CLI exit joins a stuck worker.
    for source in selected:
        threading.Thread(target=worker, args=(source,), daemon=True).start()
    successful, errors = {}, {}
    pending = set(selected)
    while pending:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            source, address, error = results.get(timeout=remaining)
        except queue.Empty:
            break
        pending.remove(source)
        if error:
            errors[source] = error
        else:
            successful[source] = address
    errors.update({source: 'timeout' for source in pending})
    candidates = sorted(set(successful.values()))
    status = 'detected' if candidates else 'unavailable'
    confidence = 'high' if len(successful) > 1 and len(candidates) == 1 else 'low' if candidates else 'none'
    return {'status': status, 'candidates': candidates,
            'publicSourceCidrs': [address + '/32' for address in candidates],
            'sources': dict(sorted(successful.items())),
            'confidence': confidence, 'errors': dict(sorted(errors.items()))}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    manual = parser.add_mutually_exclusive_group()
    manual.add_argument('--ip', help='explicit public IPv4; output a /32 CIDR')
    manual.add_argument('--cidr', help='explicit canonical public IPv4 CIDR (never 0.0.0.0/0)')
    parser.add_argument('--timeout', type=float, default=6.0, help='overall discovery deadline in seconds')
    args = parser.parse_args(argv)
    try:
        if args.ip is not None or args.cidr is not None:
            cidr = validate_ipv4(args.ip) + '/32' if args.ip is not None else validate_cidr(args.cidr)
            result = {'status': 'manual', 'candidates': [cidr], 'publicSourceCidrs': [cidr], 'sources': {},
                      'confidence': 'manual', 'errors': {}}
        else:
            result = discover(timeout=args.timeout)
    except (ValueError, TypeError):
        result = {'status': 'invalid', 'candidates': [], 'publicSourceCidrs': [], 'sources': {},
                  'confidence': 'none', 'errors': {'input': 'invalid public IPv4/CIDR or timeout'}}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return {'detected': 0, 'manual': 0, 'invalid': 2, 'unavailable': 5}[result['status']]


if __name__ == '__main__':
    raise SystemExit(main())
