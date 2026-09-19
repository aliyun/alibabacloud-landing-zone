"""Exercise the actual snapshot transaction against a deterministic OSS boundary."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import hashlib
import shutil
import argparse

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


def read_json(path):
    return json.loads(Path(path).read_text())


class MemoryOss:
    def __init__(self):
        self.objects = {}
        self.region = 'cn-hangzhou'
        self.account_id = '1234567890123456'
        self.fail_key = None
        self.get_keys = []
        self.head_keys = []

    def identity(self):
        return self.account_id

    def ensure_bucket(self, deployment_id):
        return 'aw-ops-fixture'

    def discover(self):
        return [{'bucket': 'aw-ops-fixture', 'accountUid': self.account_id,
                 'region': self.region, 'deploymentId': 'aw-fixture'}] if self.objects else []

    def head(self, bucket, key):
        self.head_keys.append(key)
        if key == self.fail_key:
            raise RuntimeError("simulated metadata failure")
        content = self.objects.get((bucket, key))
        return None if content is None else len(content)

    def get(self, bucket, key):
        self.get_keys.append(key)
        if key == self.fail_key:
            raise RuntimeError('simulated transport failure')
        return self.objects.get((bucket, key))

    def put(self, bucket, key, data, create_only=False):
        if key == self.fail_key:
            raise RuntimeError('simulated transport failure')
        if create_only and (bucket, key) in self.objects:
            raise RuntimeError('conditional object already exists')
        self.objects[bucket, key] = data

    def delete(self, bucket, key):
        self.objects.pop((bucket, key), None)


class OperationsStoreTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((SCRIPTS / 'operations-store.py').is_file(), 'cloud-first state coordinator is missing')
        sys.path.insert(0, str(SCRIPTS))
        self.addCleanup(lambda: sys.path.remove(str(SCRIPTS)))
        spec = importlib.util.spec_from_file_location('operations_store_under_test', SCRIPTS / 'operations-store.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.cloud = MemoryOss()
        self.manager = self.module.OperationsState(self.cloud)
        self.identity = {'bucket': 'aw-ops-fixture', 'accountUid': self.cloud.account_id,
                         'region': self.cloud.region, 'deploymentId': 'aw-fixture'}
        self.bundle = {'manifest': {'deploymentId': 'aw-fixture', 'accountUid': self.cloud.account_id,
                                   'region': self.cloud.region, 'upgrade': {'databaseMutationStarted': True}},
                       'files': {'deploy/protected/application.env': b'KEY=unchanged-secret\n',
                                 'upgrade/releases/0/auto-wonder.jar': b'original-sealed-jar'}}

    def test_commit_download_preserves_database_boundary_and_sensitive_file(self):
        current = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        restored, downloaded = self.manager.download(self.identity)
        self.assertEqual(current['revision'], downloaded['revision'])
        self.assertTrue(restored['manifest']['upgrade']['databaseMutationStarted'])
        self.assertEqual(b'KEY=unchanged-secret\n', restored['files']['deploy/protected/application.env'])
        self.assertNotIn(b'unchanged-secret', self.cloud.objects[('aw-ops-fixture', 'current.json')])

    def test_checkpoint_cannot_forget_previously_recorded_secrets(self):
        self.bundle['files']['deploy/terraform/terraform-secrets.env'] = b'TF_VAR_password=original'
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        del self.bundle['files']['deploy/terraform/terraform-secrets.env']
        with self.assertRaises(self.module.StateError):
            self.manager.commit(self.identity, self.bundle, first['revision'])
        self.assertEqual(first['revision'], self.manager.current(self.identity)['revision'])

    def test_stale_writer_cannot_replace_newer_state(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        changed = copy.deepcopy(self.bundle)
        changed['manifest']['upgrade']['databaseMigration'] = {'status': 'failed'}
        second = self.manager.commit(self.identity, changed, expected_revision=first['revision'])
        with self.assertRaises(self.module.StateError):
            self.manager.commit(self.identity, self.bundle, expected_revision=first['revision'])
        self.assertEqual(second['revision'], json.loads(self.cloud.get('aw-ops-fixture', 'current.json'))['revision'])

    def test_upload_failure_does_not_publish_new_current_and_releases_owned_lock(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        self.cloud.fail_key = 'current.json'
        with self.assertRaises(Exception):
            self.manager.commit(self.identity, self.bundle, expected_revision=first['revision'])
        self.cloud.fail_key = None
        self.assertEqual(first['revision'], json.loads(self.cloud.get('aw-ops-fixture', 'current.json'))['revision'])
        self.assertNotIn(('aw-ops-fixture', 'write-lock.json'), self.cloud.objects)

    def test_foreign_lock_is_not_removed(self):
        self.cloud.put('aw-ops-fixture', 'write-lock.json', b'other owner')
        with self.assertRaises(Exception):
            self.manager.commit(self.identity, self.bundle, expected_revision=None)
        self.assertEqual(b'other owner', self.cloud.get('aw-ops-fixture', 'write-lock.json'))

    def test_corrupt_object_is_rejected_before_restore(self):
        self.manager.commit(self.identity, self.bundle, expected_revision=None)
        key = next(key for bucket, key in self.cloud.objects if key.startswith('deploy/objects/'))
        self.cloud.objects[('aw-ops-fixture', key)] = b'corrupted'
        with self.assertRaises(self.module.StateError):
            self.manager.download(self.identity)

    def test_wrong_account_is_rejected(self):
        identity = {**self.identity, 'accountUid': '0000000000000000'}
        with self.assertRaises(self.module.StateError):
            self.manager.commit(identity, self.bundle, expected_revision=None)
        self.assertFalse(self.cloud.objects)

    def test_current_pointer_checksum_prevents_mixed_revisions(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        changed = copy.deepcopy(self.bundle)
        changed['manifest']['upgrade']['databaseMutationStarted'] = False
        second = self.manager.commit(self.identity, changed, expected_revision=first['revision'])
        second['records']['upgrade'] = first['records']['upgrade']
        self.cloud.objects[('aw-ops-fixture', 'current.json')] = json.dumps(second).encode()
        with self.assertRaises(self.module.StateError):
            self.manager.download(self.identity)

    def test_bootstrap_can_resume_and_become_complete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'bootstrap'
            tf = root / 'deployments/aw-fixture/terraform'
            tf.mkdir(parents=True)
            manifest = tf.parent / 'deployment-manifest.json'
            manifest.write_text(json.dumps({'accountUid': self.cloud.account_id, 'region': self.cloud.region,
                                           'deploymentId': 'aw-fixture', 'localContext': {'terraformDirectory': str(tf)}}))
            binding = self.manager.save(manifest, root, allow_incomplete=True, initialize=True)
            self.assertFalse(binding['ready'])
            fresh = Path(temporary) / 'fresh'
            fresh.mkdir()
            result = self.manager.restore(binding, fresh)
            self.assertEqual('deployment-resume-required', result['status'])
            self.assertFalse(read_json(result['manifest'])['operationsStore']['ready'])
            complete_root = Path(temporary) / 'finished'
            finished = self.fixture(complete_root)
            data = read_json(finished)
            data['operationsStore'] = binding
            finished.write_text(json.dumps(data))
            ready = self.manager.save(finished, complete_root, allow_incomplete=True)
            self.assertTrue(ready['ready'])

    def test_repeated_snapshot_reuses_content_objects(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        original_keys = set(self.cloud.objects)
        self.manager.commit(self.identity, self.bundle, expected_revision=first['revision'])
        self.assertEqual(original_keys, set(self.cloud.objects))

    def test_checkpoint_reuses_verified_artifact_without_body_download(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        jar = next(key for _, key in self.cloud.objects if key.startswith('upgrade/objects/'))
        self.bundle['files']['upgrade/releases/1/auto-wonder.jar'] = self.bundle['files']['upgrade/releases/0/auto-wonder.jar']
        self.cloud.get_keys.clear()
        self.manager.commit(self.identity, self.bundle, first['revision'])
        self.assertNotIn(jar, self.cloud.get_keys)
        self.assertEqual(1, self.cloud.head_keys.count(jar))

    def test_reused_artifact_missing_size_change_or_head_failure_stops_commit(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        jar = next(key for _, key in self.cloud.objects if key.startswith('upgrade/objects/'))
        original = self.cloud.objects[('aw-ops-fixture', jar)]
        for damage in ('missing', 'size', 'denied'):
            with self.subTest(damage=damage):
                self.cloud.objects[('aw-ops-fixture', jar)] = original
                self.cloud.fail_key = None
                if damage == 'missing':
                    del self.cloud.objects[('aw-ops-fixture', jar)]
                elif damage == 'size':
                    self.cloud.objects[('aw-ops-fixture', jar)] = b'short'
                else:
                    self.cloud.fail_key = jar
                with self.assertRaises(Exception):
                    self.manager.commit(self.identity, self.bundle, first['revision'])
                self.cloud.fail_key = None
                self.assertEqual(first['revision'], self.manager.current(self.identity)['revision'])

    def test_changed_artifact_is_uploaded_and_fully_verified(self):
        first = self.manager.commit(self.identity, self.bundle, expected_revision=None)
        changed = b'new sealed jar'
        self.bundle['files']['upgrade/releases/0/auto-wonder.jar'] = changed
        key = 'upgrade/objects/' + hashlib.sha256(changed).hexdigest()
        self.manager.commit(self.identity, self.bundle, first['revision'])
        self.assertEqual(changed, self.cloud.objects[('aw-ops-fixture', key)])
        self.assertGreaterEqual(self.cloud.get_keys.count(key), 2)

    def test_restore_still_rejects_same_size_corruption(self):
        self.manager.commit(self.identity, self.bundle, expected_revision=None)
        key = next(key for _, key in self.cloud.objects if key.startswith('upgrade/objects/'))
        self.cloud.objects[('aw-ops-fixture', key)] = b'x' * len(self.cloud.objects[('aw-ops-fixture', key)])
        with self.assertRaises(self.module.StateError):
            self.manager.download(self.identity)

    def test_missing_referenced_object_is_corruption_not_absent_deployment(self):
        self.manager.commit(self.identity, self.bundle, expected_revision=None)
        key = next(key for bucket, key in self.cloud.objects if key.startswith('upgrade/objects/'))
        del self.cloud.objects[('aw-ops-fixture', key)]
        with self.assertRaises(self.module.StateError):
            self.manager.download(self.identity)

    def fixture(self, root):
        tf = root / 'deployments/aw-fixture/terraform'
        tf.mkdir(parents=True)
        (tf / 'main.tf').write_text('terraform {}\n')
        (tf / 'terraform-secrets.env').write_text('TF_VAR_ecs_password=original\n')
        (tf / 'deployment.auto.tfvars.json').write_text('{"region":"cn-hangzhou"}\n')
        (tf / 'backend.hcl').write_text('bucket = "original-state"\nkey = "original.tfstate"\nregion = "cn-hangzhou"\n')
        env = tf.parent / 'application.env'
        env.write_bytes(b'AUTOWONDER_SECRET_MASTER_KEY=unchanged\n')
        release = tf.parent / 'release'
        release.mkdir()
        artifacts = {'releaseDirectory': str(release)}
        for key, name in [('jar', 'auto-wonder.jar'), ('migrations', 'autowonder-migrations.tar.gz')]:
            content = ('original ' + key).encode()
            (release / name).write_bytes(content)
            artifacts[key] = {'name': name, 'sha256': hashlib.sha256(content).hexdigest()}
        tags = {'Project': 'AutoWonder', 'DeploymentId': 'aw-fixture', 'Environment': 'auto-wonder-prod',
                'ManagedBy': 'AutoWonder', 'Topology': 'multi-az-ha'}
        data = {'accountUid': self.cloud.account_id, 'region': self.cloud.region, 'deploymentId': 'aw-fixture',
                'environment': 'auto-wonder-prod', 'cloudProfile': 'auto-wonder', 'tags': tags,
                'stateMode': 'remote', 'terraform': {'stateReference': str(tf / 'backend.hcl'),
                                                   'backendDirectory': str(tf), 'backendStatus': 'ready'},
                'localContext': {'terraformDirectory': str(tf), 'protectedEnvFile': str(env)},
                'deployment': {'activeCommit': 'a' * 40}, 'repositoryCommit': 'a' * 40,
                'resources': {'ecs_instance_ids': {'zone_a': 'i-example'}, 'expected_tags': tags},
                'resourceSelection': {'version':1,'status':'verified','policy':{'version':1},
                                      'selected':{'availabilityZones':['zone-a','zone-b']},
                                      'evidence':{'queriedAt':'2026-09-19T00:00:00Z','sku':'new.cloud.sku'},
                                      'selectionSha256':'a'*64,'evidenceSha256':'b'*64},
                'artifacts': artifacts, 'upgrade': {'databaseMutationStarted': True,
                                                 'databaseMigration': {'status': 'failed'}}}
        manifest = tf.parent / 'deployment-manifest.json'
        manifest.write_text(json.dumps(data))
        return manifest

    def test_delete_local_directories_and_restore_full_working_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'original'
            manifest = self.fixture(root)
            original_selection = read_json(manifest)['resourceSelection']
            original_resources = read_json(manifest)['resources']
            binding = self.manager.save(manifest, root, initialize=True)
            shutil.rmtree(root)
            fresh = Path(temporary) / 'new-computer'
            fresh.mkdir()
            result = self.manager.restore(binding, fresh)
            recovered = json.loads(Path(result['manifest']).read_text())
            self.assertEqual(original_selection, recovered['resourceSelection'])
            self.assertEqual(original_resources, recovered['resources'])
            self.assertTrue(recovered['upgrade']['databaseMutationStarted'])
            self.assertEqual('failed', recovered['upgrade']['databaseMigration']['status'])
            self.assertEqual(b'AUTOWONDER_SECRET_MASTER_KEY=unchanged\n', Path(recovered['localContext']['protectedEnvFile']).read_bytes())
            self.assertTrue((Path(result['manifest']).parent / 'discovery.json').exists())
            self.assertEqual('original-state', Path(recovered['terraform']['stateReference']).read_text().split('"')[1])
            self.manager.assert_current(result['manifest'])

    def test_cloud_resolve_never_uses_stale_local_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            self.manager.save(manifest, root, initialize=True)
            stale = json.loads(manifest.read_text())
            stale['upgrade']['databaseMutationStarted'] = False
            manifest.write_text(json.dumps(stale))
            args = argparse.Namespace(project_root=str(root), manifest=str(manifest),
                                      deployment_id=None, region=None, deployment_dir=None)
            with mock.patch.object(self.module, 'verify_legacy', side_effect=AssertionError('must not import local')):
                result = self.module.resolve(args, lambda region: self.cloud)
            self.assertTrue(json.loads(Path(result['manifest']).read_text())['upgrade']['databaseMutationStarted'])

    def test_corrupt_cloud_state_does_not_trigger_legacy_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            self.manager.save(manifest, root, initialize=True)
            self.cloud.objects[('aw-ops-fixture', 'current.json')] = b'broken'
            args = argparse.Namespace(project_root=str(root), manifest=str(manifest),
                                      deployment_id=None, region=None, deployment_dir=None)
            with mock.patch.object(self.module, 'upgrade_info', side_effect=AssertionError('no fallback')):
                with self.assertRaises(self.module.StateError):
                    self.module.resolve(args, lambda region: self.cloud)

    def test_import_keeps_newer_upgrade_state_and_original_deployment_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = self.fixture(root)
            data = read_json(original)
            data['billing'] = {'strategy': 'subscription-first'}
            original.write_text(json.dumps(data))
            current = copy.deepcopy(data)
            current.pop('billing')
            current['deployment']['activeCommit'] = 'b' * 40
            current['upgrade']['databaseMigration']['status'] = 'running'
            working = root / 'upgrade-info/aw-fixture/manifest.json'
            working.parent.mkdir(parents=True)
            working.write_text(json.dumps(current))
            self.module.rebuild_discovery(root, working)
            args = argparse.Namespace(project_root=str(root), manifest=str(original),
                                      deployment_id=None, region=None, deployment_dir=None)
            with mock.patch.object(self.module, 'verify_legacy') as verify:
                result = self.module.resolve(args, lambda region: self.cloud)
            recovered = read_json(result['manifest'])
            self.assertEqual('b' * 40, recovered['deployment']['activeCommit'])
            self.assertEqual('running', recovered['upgrade']['databaseMigration']['status'])
            self.assertEqual('subscription-first', recovered['billing']['strategy'])
            self.assertEqual('local-imported-to-oss', result['source'])
            verify.assert_called_once()

    def test_access_denial_is_never_local_absence(self):
        args = argparse.Namespace(project_root='.', manifest=None, deployment_id=None,
                                  region='cn-hangzhou', deployment_dir=None)
        with mock.patch.object(self.cloud, 'discover', side_effect=self.module.StateError('Access denied')):
            with mock.patch.object(self.module, 'upgrade_info', side_effect=AssertionError('must not fall back')):
                with self.assertRaises(self.module.StateError):
                    self.module.resolve(args, lambda region: self.cloud)

    def test_explicit_account_mismatch_prevents_cross_account_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = self.fixture(root)
            data = read_json(original)
            data['accountUid'] = '9999999999999999'
            original.write_text(json.dumps(data))
            args = argparse.Namespace(project_root=str(root), manifest=str(original),
                                      deployment_id=None, region=None, deployment_dir=None)
            with self.assertRaises(self.module.StateError):
                self.module.resolve(args, lambda region: self.cloud)
            self.assertFalse(self.cloud.objects)

    def test_state_migration_intent_is_durable_before_terraform_and_blocks_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            data = read_json(manifest)
            tf = Path(data['localContext']['terraformDirectory'])
            data['stateMode'] = 'local'
            (tf / 'terraform.tfstate').write_text('{"lineage":"original","serial":1}')
            data['terraform']['stateReference'] = str(tf / 'terraform.tfstate')
            manifest.write_text(json.dumps(data))
            self.manager.save(manifest, root, initialize=True)

            def fail_after_submission(path, store):
                bundle, current = self.manager.download(self.identity)
                self.assertEqual('unknown', bundle['manifest']['operationsMigration']['status'])
                self.assertIsNotNone(store.get('aw-ops-fixture', 'write-lock.json'))
                raise RuntimeError('simulated interrupted Terraform migration')

            with mock.patch('operations_backend.migrate_local_state', side_effect=fail_after_submission):
                with self.assertRaises(RuntimeError):
                    self.manager.migrate_backend(manifest, root)
            with mock.patch('operations_backend.migrate_local_state', side_effect=AssertionError('must not retry')):
                with self.assertRaises(self.module.StateError):
                    self.manager.migrate_backend(manifest, root)


if __name__ == '__main__':
    unittest.main()
