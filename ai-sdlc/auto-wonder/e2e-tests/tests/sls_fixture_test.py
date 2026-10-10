"""HTTP boundary tests for the isolated SLS protocol fixture."""
import importlib.util
import json
import pathlib
import subprocess
import sys
import threading
import unittest
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


class SlsTests(unittest.TestCase):
    def test_mode_is_start_only(self):
        result = subprocess.run([str(ROOT / 'verify.sh'), '--check', '--with-sls',
                                 '--project-root', str(ROOT.parent)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--with-sls requires --start', result.stderr)

    def test_fixture_exists(self):
        self.assertTrue((ROOT / 'sls_fixture.py').is_file(), 'SLS protocol fixture missing')

    def test_protocol_and_stream_gate(self):
        path = ROOT / 'sls_fixture.py'
        self.assertTrue(path.is_file(), 'SLS protocol fixture missing')
        spec = importlib.util.spec_from_file_location('sls_fixture', path)
        fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture)
        server = fixture.make_server('127.0.0.1', 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f'http://127.0.0.1:{server.server_port}'
        try:
            self.assertFalse(fixture.check_receipts(url, timeout=0)['success'])
            headers = {'Content-Type': 'application/x-protobuf', 'x-log-apiversion': '0.6.0',
                       'x-log-bodyrawsize': '12', 'x-log-compresstype': 'lz4',
                       'Authorization': 'LOG dummy:signature', 'Host': 'e2e.sls-fixture.local',
                       'x-log-signaturemethod': 'hmac-sha1'}
            def post(store, body=b'compressed-protobuf', supplied=headers):
                return urllib.request.urlopen(urllib.request.Request(
                    url + '/logstores/' + store + '/shards/lb', data=body, headers=supplied))
            for index, store in enumerate(fixture.STREAMS):
                if index < len(fixture.STREAMS):
                    self.assertFalse(fixture.check_receipts(url, timeout=0)['success'],
                                     'Every stream is required')
                with post(store) as response:
                    self.assertEqual(response.status, 200)
                    self.assertTrue(response.headers['x-log-requestid'])
            receipt = fixture.check_receipts(url, timeout=0)
            self.assertTrue(receipt['success'])
            self.assertEqual(set(receipt['streams']), set(fixture.STREAMS))
            self.assertNotIn('signature', json.dumps(receipt))
            self.assertNotIn('compressed-protobuf', json.dumps(receipt))
            for store, body, supplied in [('other', b'x', headers), (fixture.STREAMS[0], b'', headers),
                                          (fixture.STREAMS[0], b'x', {})]:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    post(store, body, supplied)
                error.exception.close()
            self.assertFalse(fixture.check_receipts(url, timeout=0)['success'],
                             'Rejected protocol requests must fail the gate')
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertFalse(fixture.check_receipts(url, timeout=0)['success'])


if __name__ == '__main__':
    unittest.main()
