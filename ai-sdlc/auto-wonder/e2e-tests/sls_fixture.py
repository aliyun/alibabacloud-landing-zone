#!/usr/bin/env python3
"""Local SLS HTTP protocol receiver, not cloud authentication or ingestion proof.

Only counters are retained; request bodies and authentication headers are discarded.
"""
import argparse
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STREAMS = ('e2e-system', 'e2e-business', 'e2e-metrics')
MAX_BODY = 16 * 1024 * 1024


def make_server(host, port):
    lock = threading.Lock()
    streams = {name: {'requests': 0, 'bytes': 0} for name in STREAMS}
    rejected = 0

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Never log headers, URLs, payloads, or credentials.

        def reply(self, status, data):
            body = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('x-log-requestid', '00000000000000000000000000000001')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != '/receipts':
                self.reply(404, {})
                return
            with lock:
                self.reply(200, {'streams': streams, 'rejectedRequests': rejected})

        def do_POST(self):
            nonlocal rejected
            self.connection.settimeout(5)
            store = next((name for name in STREAMS
                          if self.path == f'/logstores/{name}/shards/lb'), None)
            try:
                size = int(self.headers.get('Content-Length', '0'))
                raw_size = int(self.headers.get('x-log-bodyrawsize', '0'))
            except ValueError:
                size = raw_size = 0
            valid = (store is not None and 0 < size <= MAX_BODY and raw_size > 0
                     and self.headers.get('Content-Type') == 'application/x-protobuf'
                     and self.headers.get('x-log-apiversion') == '0.6.0'
                     and self.headers.get('x-log-compresstype') in ('lz4', 'deflate')
                     and self.headers.get('x-log-signaturemethod') == 'hmac-sha1'
                     and self.headers.get('Authorization', '').startswith('LOG ')
                     and self.headers.get('Host') == 'e2e.sls-fixture.local')
            if valid:
                try:
                    valid = len(self.rfile.read(size)) == size
                except (TimeoutError, OSError):
                    valid = False
            with lock:
                if valid:
                    streams[store]['requests'] += 1
                    streams[store]['bytes'] += size
                else:
                    rejected += 1
            self.reply(200 if valid else 400, {} if valid else {'errorCode': 'InvalidProtocol'})

    return ThreadingHTTPServer((host, port), Handler)


def check_receipts(url, timeout=75):
    deadline = time.monotonic() + timeout
    receipt = {'streams': {}, 'rejectedRequests': 0}
    while True:
        reachable = False
        try:
            with urllib.request.urlopen(url + '/receipts', timeout=2) as response:
                receipt = json.load(response)
            reachable = True
        except (OSError, ValueError, urllib.error.URLError):
            pass
        success = (reachable and receipt.get('rejectedRequests') == 0
                   and all(receipt.get('streams', {}).get(name, {}).get('requests', 0) > 0
                           and receipt['streams'][name].get('bytes', 0) > 0 for name in STREAMS))
        if success or time.monotonic() >= deadline:
            return {**receipt, 'success': success, 'receiverReachable': reachable,
                    'scope': 'local-sls-protocol'}
        time.sleep(min(1, max(0, deadline - time.monotonic())))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--timeout', type=float, default=75)
    args = parser.parse_args()
    if args.check:
        result = check_receipts('http://127.0.0.1:80', args.timeout)
        print(json.dumps(result, indent=2))
        raise SystemExit(0 if result['success'] else 1)
    make_server('0.0.0.0', 80).serve_forever()
