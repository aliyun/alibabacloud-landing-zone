import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'operations_oss.py'


class OssTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), 'OSS transport implementation is missing')
        spec = importlib.util.spec_from_file_location('operations_oss', SCRIPT)
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = Path(self.temp.name) / 'config.json'
        self.config.write_text(json.dumps({'profiles': [{'name': 'auto-wonder', 'access_key_id': 'TEST_AK', 'access_key_secret': 'TEST_SECRET', 'sts_token': 'TEST_TOKEN'}]}))
        self.env = patch.dict(os.environ, {'ALIBABA_CLOUD_CLI_CONFIG_FILE': str(self.config)})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.commands = []
        self.objects = {}
        self.error = None
        self.versioning = {}
        self.listing = {'Buckets': []}
        self.head_response = {'Header': {'Content-Length': ['73400320']}}
        self.private_files = []
        self.runner = patch.object(self.mod.subprocess, 'run', side_effect=self.run_command)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def run_command(self, cmd, **kwargs):
        self.commands.append(cmd)
        if '--help' in cmd:
            return subprocess.CompletedProcess(cmd, 0, b'--forbid-overwrite --object-acl --server-side-encryption --ignore-env-var', b'')
        if 'GetCallerIdentity' in cmd:
            return subprocess.CompletedProcess(cmd, 0, b'{"AccountId":"12345"}', b'')
        config = Path(cmd[cmd.index('--config-file') + 1])
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertIn('TEST_SECRET', config.read_text())
        self.private_files.append(config)
        if self.error:
            return subprocess.CompletedProcess(cmd, 1, b'', self.error)
        action = cmd[cmd.index('api') + 1] if 'api' in cmd else 'cp'
        output = {}
        if action == 'get-bucket-info' and 'identity.json' not in self.objects:
            return subprocess.CompletedProcess(cmd, 1, b'', b'Error Code: NoSuchBucket')
        if action == 'get-bucket-versioning':
            output = self.versioning
        elif action == 'head-object':
            output = self.head_response
        elif action == 'list-buckets':
            output = self.listing
        elif action == 'put-object':
            key = cmd[cmd.index('--key') + 1]
            if '--forbid-overwrite' in cmd and key in self.objects:
                return subprocess.CompletedProcess(cmd, 1, b'', b'Error Code: FileAlreadyExists TEST_SECRET')
            source = Path(cmd[cmd.index('--body') + 1].removeprefix('file://'))
            self.assertEqual(source.stat().st_mode & 0o777, 0o600)
            self.private_files.append(source)
            self.objects[key] = source.read_bytes()
        elif action == 'cp':
            i = cmd.index('cp')
            key = cmd[i + 1].split('/', 3)[-1]
            if key not in self.objects:
                return subprocess.CompletedProcess(cmd, 1, b'', b'Error Code: NoSuchKey')
            Path(cmd[i + 2]).write_bytes(self.objects[key])
        return subprocess.CompletedProcess(cmd, 0, json.dumps(output).encode(), b'')

    def test_roundtrip_and_credentials_never_in_arguments(self):
        store = self.mod.OssStore('cn-hangzhou')
        self.assertEqual(store.identity(), '12345')
        store.put('aw-ops-demo-123', 'a', b'\x00secret payload\xff')
        self.assertEqual(store.get('aw-ops-demo-123', 'a'), b'\x00secret payload\xff')
        for cmd in self.commands:
            for secret in ('TEST_AK', 'TEST_SECRET', 'TEST_TOKEN', 'secret payload'):
                self.assertNotIn(secret, repr(cmd))
        self.assertTrue(all(not p.exists() for p in self.private_files))

    def test_only_exact_missing_codes_allow_absence(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        for code in ('NoSuchKey', 'NoSuchBucket'):
            self.error = f'Error Code: {code}'.encode()
            self.assertIsNone(store.get('aw-ops-demo-123', 'a'))
        for raw in (b'Error Code: AccessDenied TEST_SECRET NoSuchKey', b'timeout NoSuchKey', b'Error Code: NoSuchKeySuffix', b'HTTP 404'):
            self.error = raw
            with self.assertRaises(self.mod.StoreError) as caught:
                store.get('aw-ops-demo-123', 'a')
            self.assertNotIn('TEST_SECRET', str(caught.exception))

    def test_create_only_is_atomic_and_conflict_is_distinct(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        store.put('aw-ops-demo-123', 'lock.json', b'first', create_only=True)
        with self.assertRaises(self.mod.ConflictError):
            store.put('aw-ops-demo-123', 'lock.json', b'second', create_only=True)
        self.assertEqual(self.objects['lock.json'], b'first')

    def test_versioned_and_suspended_buckets_reject_atomic_create(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        for status in ('Enabled', 'Suspended'):
            self.versioning = {'Status': status}
            with self.assertRaises(self.mod.StoreError):
                store.put('aw-ops-demo-123', 'lock.json', b'x', create_only=True)
        self.assertNotIn('lock.json', self.objects)

    def test_bucket_name_identity_and_private_encryption(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        bucket = store.ensure_bucket('Deployment A')
        self.assertEqual(bucket, store.ensure_bucket('Deployment A'))
        self.assertRegex(bucket, r'^aw-ops-deployment-a-[a-f0-9]{12}$')
        identity = json.loads(self.objects['identity.json'])
        self.assertEqual(identity['accountUid'], '12345')
        self.assertEqual(identity['deploymentId'], 'Deployment A')
        self.assertIn('put-bucket-encryption', repr(self.commands))
        self.assertNotIn('put-bucket-versioning', repr(self.commands))
        self.assertIn('private', repr(self.commands))

    def test_discovery_requires_matching_identity(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        bucket = store.ensure_bucket('demo')
        self.listing = {'Buckets': [{'Name': bucket, 'Location': 'oss-cn-hangzhou'}]}
        found = store.discover()
        self.assertEqual(found[0]['bucket'], bucket)
        self.assertEqual(found[0]['deploymentId'], 'demo')
        self.objects['identity.json'] = b'{"accountUid":"other"}'
        with self.assertRaises(self.mod.StoreError):
            store.discover()

    def test_malformed_listing_does_not_look_like_no_deployments(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        self.listing = {'unexpected': 'shape'}
        with self.assertRaises(self.mod.StoreError):
            store.discover()

    def test_cli_failure_cleans_sensitive_temporary_files(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        self.error = b'Error Code: AccessDenied TEST_SECRET'
        with self.assertRaises(self.mod.StoreError):
            store.put('aw-ops-demo-123', 'x', b'protected')
        self.assertTrue(all(not p.exists() for p in self.private_files))

    def test_discovery_pagination_loop_fails_closed(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        self.listing = {'Buckets': [], 'IsTruncated': True, 'NextMarker': 'same'}
        with self.assertRaises(self.mod.StoreError):
            store.discover()

    def test_delete_propagates_failure_without_remote_text(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        store.delete('aw-ops-demo-123', 'lock.json')
        self.error = b'Error Code: AccessDenied TEST_SECRET'
        with self.assertRaises(self.mod.StoreError) as caught:
            store.delete('aw-ops-demo-123', 'lock.json')
        self.assertNotIn('TEST_SECRET', str(caught.exception))

    def test_credentials_require_exact_named_profile(self):
        self.config.write_text(json.dumps({'profiles': [{'name': 'default', 'access_key_id': 'TEST_AK', 'access_key_secret': 'TEST_SECRET'}]}))
        with self.assertRaises(self.mod.StoreError):
            self.mod.OssStore('cn-hangzhou', '12345').get('aw-ops-demo-123', 'a')
        self.assertTrue(all('--help' in cmd or 'GetCallerIdentity' in cmd for cmd in self.commands))

    def test_windows_directory_acl_is_restricted_before_credentials(self):
        self.assertTrue(hasattr(self.mod, '_protect_directory'))
        with patch.object(self.mod.os, 'name', 'nt'), patch.object(self.mod, '_run') as run:
            run.side_effect = [subprocess.CompletedProcess([], 0, b'"DOMAIN\\tony","S-1-5-21-123-456-789-1001"', b''), subprocess.CompletedProcess([], 0, b'', b'')]
            self.mod._protect_directory('private-dir')
            self.assertEqual(run.call_args.args[0], ['icacls', 'private-dir', '/inheritance:r', '/grant:r', '*S-1-5-21-123-456-789-1001:(OI)(CI)F'])

    def test_expected_account_is_checked_against_active_identity(self):
        store = self.mod.OssStore('cn-hangzhou', '99999')
        with self.assertRaises(self.mod.StoreError):
            store.put('aw-ops-demo-123', 'a', b'secret')
        self.assertFalse(self.private_files)

    def test_real_cli_empty_bucket_response_is_absence(self):
        self.listing = {'Buckets': None}
        self.assertEqual(self.mod.OssStore('cn-hangzhou', '12345').discover(), [])

    def test_real_cli_single_bucket_response_is_discovered(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        bucket = store.ensure_bucket('demo')
        self.listing = {'Buckets': {'Bucket': {'Name': bucket, 'Location': 'oss-cn-hangzhou'}}}
        self.assertEqual(store.discover()[0]['bucket'], bucket)

    def test_real_cli_punctuated_errors_preserve_missing_and_conflict(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        for code in ('NoSuchKey', 'NoSuchBucket'):
            self.error = ('Error: operation error GetObject: Error returned by Service. \n'
                          'Http Status Code: 404. \nError Code: ' + code + '. \nRequest Id: DUMMY.').encode()
            self.assertIsNone(store.get('aw-ops-demo-123', 'missing'))
        self.error = None
        store.put('aw-ops-demo-123', 'lock.json', b'first', create_only=True)
        original = self.run_command
        def punctuated_conflict(args, **kwargs):
            result = original(args, **kwargs)
            if result.returncode and b'FileAlreadyExists' in result.stderr:
                result.stderr = b'Error: operation error PutObject.\nError Code: FileAlreadyExists.\nRequest Id: DUMMY.'
            return result
        self.runner.stop()
        with patch.object(self.mod.subprocess, 'run', side_effect=punctuated_conflict):
            with self.assertRaises(self.mod.ConflictError):
                store.put('aw-ops-demo-123', 'lock.json', b'second', create_only=True)
        self.assertEqual(self.objects['lock.json'], b'first')

    def test_head_returns_real_cli_content_length_without_download(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        self.assertTrue(hasattr(store, 'head'))
        self.assertEqual(store.head('aw-ops-demo-123', 'large.jar'), 73400320)
        self.head_response = {'Header': {'Content-Length': ['0']}}
        self.assertEqual(store.head('aw-ops-demo-123', 'empty'), 0)
        self.assertTrue(any('head-object' in cmd for cmd in self.commands))
        self.assertFalse(any('cp' in cmd or 'get-object' in cmd for cmd in self.commands))

    def test_head_rejects_missing_malformed_or_ambiguous_length(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        self.assertTrue(hasattr(store, 'head'))
        invalid = [{}, {'Header': None}, {'Header': {}},
                   {'Header': {'Content-Length': ['1'], 'content-length': ['2']}}]
        invalid += [{'Header': {'Content-Length': value}} for value in
                    (None, 3, True, '3', [], ['1', '2'], [-1], [True], ['-1'], ['1.0'], ['+1'], ['1e3'], [' 1'], ['１'], [''])]
        for response in invalid:
            with self.subTest(response=response):
                self.head_response = response
                with self.assertRaises(self.mod.StoreError):
                    store.head('aw-ops-demo-123', 'large.jar')

    def test_head_only_exact_absence_returns_none(self):
        store = self.mod.OssStore('cn-hangzhou', '12345')
        self.assertTrue(hasattr(store, 'head'))
        for code in ('NoSuchKey', 'NoSuchBucket'):
            self.error = ('Error Code: ' + code + '.\n').encode()
            self.assertIsNone(store.head('aw-ops-demo-123', 'missing'))
        for raw in (b'Error Code: AccessDenied. TEST_SECRET', b'HTTP 404', b'timeout NoSuchKey', b'Error Code: NoSuchKeySuffix.'):
            self.error = raw
            with self.assertRaises(self.mod.StoreError) as caught:
                store.head('aw-ops-demo-123', 'large.jar')
            self.assertNotIn('TEST_SECRET', str(caught.exception))

    def test_legacy_cli_rejected_before_cloud_calls(self):
        self.runner.stop()
        with patch.object(self.mod.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'legacy', b'')) as run:
            with self.assertRaises(self.mod.StoreError):
                self.mod.OssStore('cn-hangzhou', '12345').put('aw-ops-demo-123', 'x', b'x')
            self.assertTrue(all('--help' in call.args[0] for call in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
