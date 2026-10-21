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


    def test_regionless_discovery_restores_international_deployment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.cloud.region = 'ap-southeast-1'
            manifest = self.fixture(root)
            self.manager.save(manifest, root, initialize=True)
            shutil.rmtree(root / 'deployments')
            args = argparse.Namespace(project_root=str(root), manifest=None,
                                      deployment_id=None, region=None, deployment_dir=None)
            queried = []
            def store(region):
                queried.append(region)
                if region == self.cloud.region:
                    return self.cloud
                return MemoryOss()
            with mock.patch.object(self.module, 'cloud_regions', return_value=['cn-hangzhou', 'ap-southeast-1']):
                result = self.module.resolve(args, store)
            self.assertEqual(['cn-hangzhou', 'ap-southeast-1'], queried)
            self.assertEqual('ap-southeast-1', read_json(result['manifest'])['region'])

    def test_explicit_region_does_not_require_region_catalogue(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            self.manager.save(manifest, root, initialize=True)
            args = argparse.Namespace(project_root=str(root), manifest=None,
                                      deployment_id=None, region='cn-hangzhou', deployment_dir=None)
            with mock.patch.object(self.module, 'cloud_regions', side_effect=AssertionError('unneeded API')):
                self.module.resolve(args, lambda region: self.cloud)

    def test_catalogue_failure_does_not_fall_back_to_local_state(self):
        args = argparse.Namespace(project_root=None, manifest=None,
                                  deployment_id=None, region=None, deployment_dir=None)
        factory = mock.Mock()
        with mock.patch.object(self.module, 'cloud_regions', side_effect=self.module.StateError('unavailable')):
            with self.assertRaises(self.module.StateError):
                self.module.resolve(args, factory)
        factory.assert_not_called()

    def test_region_catalogue_is_sorted_and_deduplicated(self):
        response = {'Regions': {'Region': [{'RegionId': value} for value in
                    ['cn-hangzhou', 'ap-southeast-1', 'cn-hangzhou']]}}
        result = mock.Mock(returncode=0, stdout=json.dumps(response).encode())
        with mock.patch.object(self.module.subprocess, 'run', return_value=result) as run:
            self.assertEqual(['ap-southeast-1', 'cn-hangzhou'], self.module.cloud_regions())
        self.assertEqual(['aliyun', 'ecs', 'DescribeRegions', '--region', 'cn-beijing',
                          '--profile', 'auto-wonder'], run.call_args.args[0])
        self.assertEqual(30, run.call_args.kwargs['timeout'])

    def test_invalid_catalogue_is_rejected_without_diagnostics(self):
        responses = [b'not-json-secret', b'null', b'[]', b'{}',
                     b'{"Regions":{"Region":[]}}', b'{"Regions":{"Region":[null]}}',
                     b'{"Regions":{"Region":[{"RegionId":"invalid secret"}]}}',
                     b'{"Code":"Denied","Regions":{"Region":[{"RegionId":"cn-hangzhou"}]}}',
                     b'{"Success":false,"Regions":{"Region":[{"RegionId":"cn-hangzhou"}]}}']
        for response in responses:
            with self.subTest(response=response), mock.patch.object(
                    self.module.subprocess, 'run', return_value=mock.Mock(returncode=0, stdout=response)):
                with self.assertRaisesRegex(self.module.StateError, '^Public cloud region catalogue is unavailable or invalid$'):
                    self.module.cloud_regions()
        for error in [OSError('secret'), self.module.subprocess.TimeoutExpired('secret', 30)]:
            with mock.patch.object(self.module.subprocess, 'run', side_effect=error):
                with self.assertRaisesRegex(self.module.StateError, '^Public cloud region catalogue is unavailable or invalid$'):
                    self.module.cloud_regions()
        with mock.patch.object(self.module.subprocess, 'run', return_value=mock.Mock(returncode=1, stdout=b'secret')):
            with self.assertRaisesRegex(self.module.StateError, '^Public cloud region catalogue is unavailable or invalid$'):
                self.module.cloud_regions()
