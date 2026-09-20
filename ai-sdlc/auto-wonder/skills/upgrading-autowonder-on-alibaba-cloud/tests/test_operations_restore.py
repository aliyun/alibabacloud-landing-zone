"""Upgrade-local OSS recovery fixtures; no deployment bundle is imported."""
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


class OperationsRestoreTests(unittest.TestCase):
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
                'artifacts': artifacts, 'upgrade': {'databaseMutationStarted': True,
                                                 'databaseMigration': {'status': 'failed'}}}
        manifest = tf.parent / 'deployment-manifest.json'
        manifest.write_text(json.dumps(data))
        return manifest

    def test_delete_local_directories_and_restore_full_working_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'original'
            manifest = self.fixture(root)
            binding = self.manager.save(manifest, root, initialize=True)
            shutil.rmtree(root)
            fresh = Path(temporary) / 'new-computer'
            fresh.mkdir()
            result = self.manager.restore(binding, fresh)
            recovered = json.loads(Path(result['manifest']).read_text())
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
