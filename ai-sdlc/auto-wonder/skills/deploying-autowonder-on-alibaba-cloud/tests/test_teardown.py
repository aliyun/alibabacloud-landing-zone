"""Offline teardown boundaries: failed cleanup must retain recovery inputs."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
from operations_oss import OssStore, StoreError

spec = importlib.util.spec_from_file_location('teardown_state', SCRIPTS / 'operations-store.py')
state = importlib.util.module_from_spec(spec)
spec.loader.exec_module(state)


class TeardownCloud(OssStore):
    def __init__(self):
        super().__init__('cn-hangzhou', '1234567890123456')
        self.account_id = self.expected_account
        self.bucket = self._bucket('aw-fixture')
        self.binding = {'bucket': self.bucket, 'accountUid': self.account_id,
                        'region': self.region, 'deploymentId': 'aw-fixture'}
        self.objects = {'identity.json': state.canonical({'schemaVersion': 1, **{k: v for k, v in self.binding.items() if k != 'bucket'}}),
                        'current.json': b'current', 'deploy/objects/' + 'a' * 64: b'secret',
                        'upgrade/records/' + 'b' * 64: b'record'}
        self.exists = True
        self.fail_key = None
        self.deleted = []

    def get(self, bucket, key):
        assert bucket == self.bucket
        return self.objects.get(key)

    def put(self, bucket, key, data, create_only=False):
        assert bucket == self.bucket
        if create_only and key in self.objects:
            raise StoreError('Existing lock')
        self.objects[key] = data

    def delete(self, bucket, key):
        assert bucket == self.bucket
        if key == self.fail_key:
            raise StoreError('Simulated transport failure')
        # The lock must still exist while data is removed.
        if key != 'write-lock.json':
            assert 'write-lock.json' in self.objects
        self.objects.pop(key, None)
        self.deleted.append(key)

    def _api(self, action, bucket=None, *args, missing=False):
        if bucket.startswith('aw-tfstate-') and action == 'get-bucket-info':
            return None
        assert bucket == self.bucket
        if action == 'get-bucket-versioning':
            return {}
        if action == 'get-bucket-info':
            return {'BucketInfo': {'Owner': {'ID': self.account_id}}} if self.exists else None
        if action == 'list-objects':
            return {'Contents': [{'Key': key} for key in self.objects], 'IsTruncated': False}
        if action == 'delete-bucket':
            if self.objects:
                raise StoreError('Bucket not empty')
            self.exists = False
            return {}
        raise AssertionError(action)


class TeardownTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cloud = TeardownCloud()
        self.manager = state.OperationsState(self.cloud)
        self.manifest = self.root / 'manifest.json'
        self.data = {'deploymentId': 'aw-fixture', 'region': self.cloud.region,
                     'accountUid': self.cloud.account_id, 'operationsStore': self.cloud.binding,
                     'terraform': {'mainDestroyVerified': True, 'backendStatus': 'destroyed',
                                   'stateBucket': 'aw-tfstate-aw-fixture-' + hashlib.sha256(b'1234567890123456|cn-hangzhou|aw-fixture').hexdigest()[:12]},
                     'phase': 'terraform-destroy', 'status': 'destroyed'}
        self.write()

    def write(self):
        self.manifest.write_text(json.dumps(self.data))

    def test_cleanup_removes_dedicated_bucket_and_stops_future_checkpoints(self):
        self.assertTrue(hasattr(self.manager, 'teardown'), 'operations teardown is missing')
        with mock.patch.object(self.manager, 'assert_current'):
            self.manager.teardown(self.manifest)
        result = state.read(self.manifest)
        self.assertFalse(self.cloud.exists)
        self.assertNotIn('operationsStore', result)
        self.assertEqual('destroyed', result['operationsTeardown']['status'])
        self.assertEqual(['current.json', 'identity.json', 'write-lock.json'], self.cloud.deleted[-3:])

    def test_cleanup_requires_verified_main_destroy_and_backend_destroy(self):
        self.assertTrue(hasattr(self.manager, 'teardown'), 'operations teardown is missing')
        for change in ({'mainDestroyVerified': False}, {'backendStatus': 'destroy-unknown'}, {'pendingOperation': 'destroy'}):
            with self.subTest(change=change):
                data = dict(self.data, terraform={**self.data['terraform'], **change})
                self.manifest.write_text(json.dumps(data))
                with self.assertRaises(state.StateError):
                    self.manager.teardown(self.manifest)
                self.assertEqual([], self.cloud.deleted)

    def test_foreign_lock_or_identity_never_allows_delete(self):
        self.assertTrue(hasattr(self.manager, 'teardown'), 'operations teardown is missing')
        self.cloud.objects['write-lock.json'] = b'another operator'
        with mock.patch.object(self.manager, 'assert_current'):
            with self.assertRaises((state.StateError, StoreError)):
                self.manager.teardown(self.manifest)
        self.assertEqual([], self.cloud.deleted)
        self.assertEqual(b'another operator', self.cloud.objects['write-lock.json'])

    def test_failure_retains_lock_local_inputs_and_allows_owned_retry(self):
        self.assertTrue(hasattr(self.manager, 'teardown'), 'operations teardown is missing')
        backend = self.root / 'backend.hcl'
        backend.write_text('bucket = "fixture"')
        self.cloud.fail_key = 'deploy/objects/' + 'a' * 64
        with mock.patch.object(self.manager, 'assert_current'):
            with self.assertRaises(StoreError):
                self.manager.teardown(self.manifest)
            self.assertTrue(backend.exists())
            self.assertIn('current.json', self.cloud.objects)
            self.assertIn('identity.json', self.cloud.objects)
            self.assertIn('write-lock.json', self.cloud.objects)
            self.cloud.fail_key = None
            self.manager.teardown(self.manifest)
        self.assertFalse(self.cloud.exists)

    def test_bucket_binding_mismatch_is_rejected_before_delete(self):
        self.assertTrue(hasattr(self.manager, 'teardown'), 'operations teardown is missing')
        self.data['operationsStore']['deploymentId'] = 'aw-other'
        self.write()
        with self.assertRaises(state.StateError):
            self.manager.teardown(self.manifest)
        self.assertEqual([], self.cloud.deleted)

    def test_state_bucket_must_be_confirmed_absent(self):
        original = self.cloud._api
        def existing_state(action, bucket=None, *args, **kwargs):
            if bucket.startswith('aw-tfstate-'):
                return {'BucketInfo': {}}
            return original(action, bucket, *args, **kwargs)
        with mock.patch.object(self.cloud, '_api', side_effect=existing_state), mock.patch.object(self.manager, 'assert_current'):
            with self.assertRaises(state.StateError):
                self.manager.teardown(self.manifest)
        self.assertEqual([], self.cloud.deleted)

    def test_stale_retry_cannot_delete_newer_revision(self):
        self.cloud.fail_key = 'deploy/objects/' + 'a' * 64
        with mock.patch.object(self.manager, 'assert_current'):
            with self.assertRaises(StoreError):
                self.manager.teardown(self.manifest)
        self.cloud.fail_key = None
        with mock.patch.object(self.manager, 'assert_current', side_effect=state.StateError('stale')):
            with self.assertRaises(state.StateError):
                self.manager.teardown(self.manifest)
        self.assertEqual([], self.cloud.deleted)

    def test_wrong_bucket_identity_and_unknown_objects_block_cleanup(self):
        original = self.cloud.objects['identity.json']
        self.cloud.objects['identity.json'] = b'{"schemaVersion": 1}'
        with self.assertRaises(StoreError):
            self.manager.teardown(self.manifest)
        self.assertEqual([], self.cloud.deleted)
        self.cloud.objects['identity.json'] = original
        self.cloud.objects['unrelated-file'] = b'keep'
        with mock.patch.object(self.manager, 'assert_current'):
            with self.assertRaises(StoreError):
                self.manager.teardown(self.manifest)
        self.assertEqual([], self.cloud.deleted)

    def test_backend_keeps_file_until_last_checkpoint_and_ops_cleanup(self):
        fixture = self.root / 'scripts'
        fixture.mkdir()
        shutil.copy(SCRIPTS / 'terraform-backend.sh', fixture)
        backend = self.root / 'deployments/aw-fixture/terraform/backend.hcl'
        backend.parent.mkdir(parents=True)
        backend.write_text('bucket = "fixture"')
        # Replace only external identity/OSS/checkpoint boundaries; execute the real shell flow.
        (fixture / 'lib.sh').write_text('''
require_no_secret_args() { :; }
require_file() { test -f "$1"; }
require_command() { :; }
json_validate() { :; }
reject_secret_keys() { :; }
configure_cloud_profile() { :; }
json_string() { jq -r "$2" "$1"; }
require_remote_submission_settled() { :; }
ensure_alicloud_profile_identity() { AUTOWONDER_IDENTITY_JSON=$(printf '{"AccountId":"%s"}' "${TEST_ACCOUNT:-1234567890123456}"); }
ossutil_preflight() { OSSUTIL_CONTRACT=v2; }
ossutil_cli() {
  # v2 misleadingly accepts unknown-command --help, but not the command.
  if [[ "$1" == rb && "${2:-}" != --help ]]; then
    echo 'no such command: rb' >&2; return 1
  fi
  if [[ "$1" == api && "${2:-}" == delete-bucket ]]; then
    printf 'yes' > "$STATE_BUCKET_REMOVED"
  fi
}
die() { echo "$*" >&2; exit 1; }
atomic_jq() {
  local file=$1; shift
  jq "$@" "$file" > "$file.next" && mv "$file.next" "$file"
  test -f "$EXPECTED_BACKEND" || { echo 'Required recovery file is missing' >&2; exit 1; }
}
AUTOWONDER_OPERATIONS_CLI="$FAKE_OPERATIONS"
''')
        fake = self.root / 'fake-operations.py'
        fake.write_text('''import os, pathlib, sys
assert sys.argv[1] == 'teardown'
assert pathlib.Path(os.environ['EXPECTED_BACKEND']).exists()
pathlib.Path(os.environ['OPS_REMOVED']).write_text('yes')
''')
        digest = hashlib.sha256(b'1234567890123456|cn-hangzhou|aw-fixture').hexdigest()[:12]
        self.data.update(environment='auto-wonder-prod', stateMode='remote')
        self.data['terraform']['stateBucket'] = 'aw-tfstate-aw-fixture-' + digest
        self.write()
        env = dict(os.environ, EXPECTED_BACKEND=str(backend), FAKE_OPERATIONS=str(fake), OPS_REMOVED=str(self.root / 'removed'),
                   STATE_BUCKET_REMOVED=str(self.root / 'state-bucket-removed'))
        command = ['bash', str(fixture / 'terraform-backend.sh'), 'destroy', '--manifest', str(self.manifest), '--project-root', str(self.root)]
        wrong_identity = subprocess.run(command, env=dict(env, TEST_ACCOUNT='9999999999999999'), capture_output=True, text=True)
        self.assertNotEqual(0, wrong_identity.returncode, 'wrong account must not delete either bucket')
        self.assertTrue(backend.exists())
        result = subprocess.run(command, env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / 'removed').exists(), 'operations bucket was omitted')
        self.assertTrue((self.root / 'state-bucket-removed').exists(), 'v2 must use its native delete-bucket API')
        self.assertFalse(backend.exists())


if __name__ == '__main__':
    unittest.main()
