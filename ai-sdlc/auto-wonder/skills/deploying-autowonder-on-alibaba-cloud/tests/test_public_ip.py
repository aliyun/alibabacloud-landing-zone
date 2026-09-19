import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.find_spec('public_ip')
if SPEC:
    import public_ip


class PublicIPTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(SPEC, 'public_ip core must exist')

    def discover(self, values, timeout=0.2):
        def fake(source, request_timeout):
            value = values[source]
            if isinstance(value, Exception):
                raise value
            return value
        with patch.object(public_ip, 'fetch_source', side_effect=fake):
            return public_ip.discover(timeout=timeout)

    def test_independent_formats_and_agreement(self):
        result = self.discover({'ipip': '当前 IP：8.8.8.8  来自于：美国 Google',
                                'ipify': '{"ip":"8.8.8.8"}',
                                'myip': '{"ip":"8.8.8.8","country":"US","cc":"US"}'})
        self.assertEqual(result['status'], 'detected')
        self.assertEqual(result['candidates'], ['8.8.8.8'])
        self.assertEqual(result['publicSourceCidrs'], ['8.8.8.8/32'])
        self.assertEqual(len(result['sources']), 3)

    def test_single_result_is_usable_without_confirmation(self):
        result = self.discover({'ipip': 'bad', 'ipify': '{"ip":"8.8.8.8"}', 'myip': TimeoutError()})
        self.assertEqual(result['status'], 'detected')
        self.assertEqual(result['confidence'], 'low')
        self.assertEqual(result['publicSourceCidrs'], ['8.8.8.8/32'])

    def test_different_addresses_include_all_unique_cidrs(self):
        result = self.discover({'ipip': '当前 IP：8.8.8.8 来自于：美国',
                                'ipify': '{"ip":"8.8.8.8"}', 'myip': '{"ip":"1.1.1.1"}'})
        self.assertEqual(result['status'], 'detected')
        self.assertEqual(result['candidates'], ['1.1.1.1', '8.8.8.8'])
        self.assertEqual(result['publicSourceCidrs'], ['1.1.1.1/32', '8.8.8.8/32'])

    def test_no_result_and_no_secret_error_echo(self):
        result = self.discover({'ipip': 'error 8.8.8.8', 'ipify': '{"ip":"::1"}',
                                'myip': RuntimeError('secret-token-example')})
        self.assertEqual(result['status'], 'unavailable')
        self.assertNotIn('secret-token-example', json.dumps(result))
        self.assertEqual(result['publicSourceCidrs'], [])

    def test_rejects_non_public_ipv4(self):
        for value in [134744072, b'\x08\x08\x08\x08', '127.0.0.1', '0.0.0.0', '10.1.1.1', '172.16.0.1', '192.168.0.1',
                      '100.64.1.1', '169.254.1.1', '192.0.2.1', '198.51.100.1',
                      '203.0.113.1', '198.18.0.1', '224.0.0.1', '240.0.0.1',
                      '255.255.255.255', '::ffff:8.8.8.8', '8.8.8.8/32', '8.8.8.8 trailing']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                public_ip.validate_ipv4(value)
        self.assertEqual(public_ip.validate_ipv4('8.8.8.8'), '8.8.8.8')

    def test_real_overall_deadline_including_process_exit(self):
        code = ('import public_ip,time,json; '
                'public_ip.fetch_source=lambda *a: (time.sleep(10), "8.8.8.8")[1]; '
                'print(json.dumps(public_ip.discover(timeout=0.05)))')
        started = time.monotonic()
        proc = subprocess.run([sys.executable, '-c', code], cwd=SCRIPTS,
                              capture_output=True, text=True, timeout=2)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual(json.loads(proc.stdout)['status'], 'unavailable')

    def test_request_timeout_and_source_selection(self):
        observed = []
        def fake(source, timeout):
            observed.append((source, timeout))
            return '{"ip":"8.8.8.8"}'
        with patch.object(public_ip, 'fetch_source', side_effect=fake):
            result = public_ip.discover(timeout=0.3, sources=['ipify'])
        self.assertEqual(result['status'], 'detected')
        self.assertEqual(observed[0][0], 'ipify')
        self.assertGreater(observed[0][1], 0)
        self.assertLessEqual(observed[0][1], 0.3)

    def test_manual_cli_and_exit_contract(self):
        for args, expected in [(['--ip', '8.8.8.8'], 0), (['--cidr', '8.8.8.0/24'], 0),
                               (['--cidr', '0.0.0.0/0'], 2), (['--cidr', '8.8.8.8/24'], 2),
                               (['--ip', '10.0.0.1'], 2)]:
            with self.subTest(args=args), contextlib.redirect_stdout(io.StringIO()) as out:
                status = public_ip.main(args)
                result = json.loads(out.getvalue())
                self.assertEqual(status, expected)
                self.assertEqual(result['status'], 'manual' if expected == 0 else 'invalid')
        for status, expected in [('detected', 0), ('unavailable', 5)]:
            with patch.object(public_ip, 'discover', return_value={'status': status}), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(public_ip.main([]), expected)

    def test_transport_respects_proxy_and_bounds_response(self):
        class Response:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self, limit):
                self.limit = limit
                return b'{"ip":"8.8.8.8"}'
        response = Response()
        captured = {}
        class Opener:
            def open(self, request, timeout):
                captured['url'] = request.full_url
                captured['headers'] = dict(request.header_items())
                captured['timeout'] = timeout
                return response
        def build(*handlers):
            captured['proxies'] = handlers[0].proxies
            return Opener()
        with patch.dict(os.environ, {'HTTPS_PROXY': 'https://user:secret@example.invalid',
                                     'AUTHORIZATION': 'Bearer secret'}), \
                patch.object(public_ip.urllib.request, 'build_opener', side_effect=build):
            body = public_ip.fetch_source('ipify', 0.25)
        self.assertEqual(public_ip.parse_source('ipify', body), '8.8.8.8')
        self.assertTrue(captured['url'].startswith('https://'))
        self.assertEqual(captured['proxies']['https'], 'https://user:secret@example.invalid')
        self.assertNotIn('Authorization', captured['headers'])
        self.assertEqual(captured['timeout'], 0.25)
        self.assertLessEqual(response.limit, 8192)

    def test_malformed_source_responses(self):
        for source, body in [('ipify', '[]'), ('myip', '{"ip":123}'),
                             ('ipify', '{"ip":"8.8.8.8","other":'),
                             ('myip', '{"ip":"192.168.0.1"}'),
                             ('ipip', '<html>8.8.8.8</html>')]:
            with self.subTest(source=source, body=body), self.assertRaises(ValueError):
                public_ip.parse_source(source, body)

    def test_invalid_timeout_and_source_list(self):
        for timeout in [0, -1, float('nan'), float('inf')]:
            with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                public_ip.discover(timeout=timeout)
        for sources in [[], ['untrusted'], ['https://example.invalid']]:
            with self.subTest(sources=sources), self.assertRaises(ValueError):
                public_ip.discover(sources=sources)

    def test_cidr_cannot_cross_reserved_network(self):
        for cidr in ['0.0.0.0/0', '8.0.0.0/6', '192.0.0.0/8', '224.0.0.0/4']:
            with self.subTest(cidr=cidr), self.assertRaises(ValueError):
                public_ip.validate_cidr(cidr)

    def test_redirects_are_refused(self):
        with self.assertRaises(ValueError):
            public_ip.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://example.invalid')

    def test_shell_manual_wrapper(self):
        proc = subprocess.run(['bash', str(SCRIPTS / 'detect-public-ip.sh'), '--ip', '8.8.8.8'],
                              capture_output=True, text=True,
                              env=dict(os.environ, AUTOWONDER_PYTHON=sys.executable))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(json.loads(proc.stdout)['status'], 'manual')


if __name__ == '__main__':
    unittest.main()
