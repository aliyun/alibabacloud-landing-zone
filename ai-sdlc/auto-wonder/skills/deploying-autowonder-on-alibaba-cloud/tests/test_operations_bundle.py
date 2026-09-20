import copy
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/operations_bundle.py'
spec = importlib.util.spec_from_file_location('operations_bundle', SCRIPT)
bundle_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle_module)


def digest(value):
    return hashlib.sha256(value).hexdigest()


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'original space'
        self.root.mkdir()
        tf = self.root / 'infra/root'
        tf.mkdir(parents=True)
        module = self.root / 'infra/module'
        module.mkdir()
        (module / 'main.tf').write_text('resource "x" "y" {}')
        (tf / 'main.tf').write_text('module "child" { source = "../module" }')
        (tf / 'terraform-secrets.env').write_bytes(b'TF_VAR_password=unchanged\n')
        (tf / 'deployment.auto.tfvars.json').write_text('{}')
        (tf / '.terraform.lock.hcl').write_text('# lock')
        (tf / 'terraform.tfstate').write_text('{"serial":42}')
        (tf / 'reviewed.tfplan').write_text('excluded')
        (self.root / 'application.env').write_text('PASSWORD=original\n')
        (self.root / 'candidate.env').write_text('PASSWORD=candidate\n')
        release = self.root / 'release'
        release.mkdir()
        artifacts = {'releaseDirectory': str(release)}
        for key, name in [('jar', 'auto-wonder.jar'), ('migrations', 'autowonder-migrations.tar.gz')]:
            contents = key.encode()
            (release / name).write_bytes(contents)
            artifacts[key] = dict(name=name, sha256=digest(contents))
        baseline = dict(schemaVersion=1, releaseId='old', artifacts=artifacts)
        baseline['sha256'] = digest(bundle_module.canonical(baseline))
        self.data = dict(deploymentId='test', stateMode='local',
            localContext=dict(terraformDirectory=str(tf), protectedEnvFile=str(self.root / 'application.env')),
            source=dict(baseline=baseline), artifacts=copy.deepcopy(artifacts),
            upgrade=dict(databaseMutationStarted=True, status='running', candidateEnvFile=str(self.root / 'candidate.env'), nodes=[{'id':'x','status':'running'}]))
        self.data['resourceSelection'] = {'version':1,'status':'verified','policy':{'version':1},
                                          'evidence':{'sku':'future.sku','queriedAt':'2026-09-19T00:00:00Z'},
                                          'selectionSha256':'a'*64,'evidenceSha256':'b'*64}
        self.manifest = self.root / 'manifest.json'
        self.save()

    def save(self):
        self.manifest.write_text(json.dumps(self.data))

    def test_delete_original_and_restore_complete_state(self):
        bundle = bundle_module.collect(self.manifest, self.root)
        self.assertNotIn(str(self.root), json.dumps(bundle['manifest']))
        self.assertFalse(any('tfplan' in x for x in bundle['files']))
        shutil.rmtree(self.root)
        restored = bundle_module.restore(bundle, Path(self.temp.name) / 'recovered')
        data = json.loads(restored.read_text())
        self.assertEqual(self.data['resourceSelection'], data['resourceSelection'])
        self.assertTrue(data['upgrade']['databaseMutationStarted'])
        self.assertEqual(data['upgrade']['nodes'][0]['status'], 'running')
        tf = Path(data['localContext']['terraformDirectory'])
        self.assertEqual((tf / 'terraform-secrets.env').read_bytes(), b'TF_VAR_password=unchanged\n')
        self.assertTrue((tf / '../module/main.tf').is_file())
        self.assertEqual(Path(data['upgrade']['candidateEnvFile']).read_text(), 'PASSWORD=candidate\n')
        baseline = data['source']['baseline']
        self.assertEqual(baseline['sha256'], digest(bundle_module.canonical({k:v for k,v in baseline.items() if k != 'sha256'})))
        self.assertTrue(data['operationsBundle']['localStateRequiresMigration'])
        self.assertEqual((tf / 'terraform.tfstate').read_text(), '{"serial":42}')
        self.assertEqual(restored.stat().st_mode & 0o777, 0o600)

    def test_legacy_environment_roundtrip_through_oss_preserves_master_key(self):
        from test_operations_store import MemoryOss
        scripts = SCRIPT.parent
        sys.path.insert(0, str(scripts))
        self.addCleanup(lambda: sys.path.remove(str(scripts)))
        spec = importlib.util.spec_from_file_location('legacy_roundtrip', scripts / 'operations-store.py')
        coordinator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(coordinator)
        cloud = MemoryOss()
        manager = coordinator.OperationsState(cloud)
        self.data['accountUid'] = cloud.account_id
        self.data['region'] = cloud.region
        self.save()
        identity = {k: self.data[k] for k in ('deploymentId', 'accountUid', 'region')}
        identity['bucket'] = 'aw-ops-fixture'
        cloud.account_id = identity['accountUid']
        cloud.region = identity['region']
        original = b'AUTOWONDER_SECRET_MASTER_KEY=c3ludGhldGljLW1hc3Rlci1rZXktMzItYnl0ZXMteHg=\nAUTOWONDER_JWT_SECRET=synthetic-jwt\n'
        (self.root / 'application.env').write_bytes(original)
        (self.root / 'candidate.env').write_bytes(original)
        bundle = bundle_module.collect(self.manifest, self.root)
        current = manager.commit(identity, bundle, None)
        self.assertTrue(current['runtimeSecretsRecorded'])
        shutil.rmtree(self.root)
        restored_bundle, _ = manager.download(identity)
        restored = bundle_module.restore(restored_bundle, Path(self.temp.name) / 'oss-restored')
        data = json.loads(restored.read_text())
        self.assertEqual(original, Path(data['localContext']['protectedEnvFile']).read_bytes())
        self.assertEqual(original, Path(data['upgrade']['candidateEnvFile']).read_bytes())
        self.assertTrue(data['upgrade']['databaseMutationStarted'])

    def test_bad_artifact_hash_fails_even_incomplete(self):
        (self.root / 'release/auto-wonder.jar').write_text('bad')
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.collect(self.manifest, self.root, True)

    def test_disposable_runtime_cache_is_not_recovery_data(self):
        before = bundle_module.collect(self.manifest, self.root)
        cache = self.root / 'skills' / '.autowonder-tools' / 'python'
        cache.mkdir(parents=True)
        (cache / 'runtime').write_bytes(b'unnecessary runtime binary')
        after = bundle_module.collect(self.manifest, self.root)
        self.assertEqual(before['files'], after['files'])
        self.assertEqual(before['manifest'], after['manifest'])

    def test_missing_secrets_fails(self):
        (self.root / 'infra/root/terraform-secrets.env').unlink()
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.collect(self.manifest, self.root)

    def test_bad_baseline_fails(self):
        self.data['source']['baseline']['releaseId'] = 'tampered'
        self.save()
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.collect(self.manifest, self.root)

    def test_restore_rejects_corruption_and_traversal(self):
        bundle = bundle_module.collect(self.manifest, self.root)
        bundle['files'][next(iter(bundle['files']))] = b'bad'
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.restore(bundle, Path(self.temp.name) / 'bad')
        bundle = bundle_module.collect(self.manifest, self.root)
        bundle['files']['../escape'] = b'bad'
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.restore(bundle, Path(self.temp.name) / 'bad')

    def test_symlink_rejected(self):
        secret = self.root / 'infra/root/terraform-secrets.env'
        secret.unlink()
        secret.symlink_to(self.root / 'application.env')
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.collect(self.manifest, self.root)

    def test_backend_credentials_removed_other_settings_preserved(self):
        backend = self.root / 'backend.hcl'
        backend.write_text('bucket = "test"\nkey = "state"\naccess_key = "private"\nsecret_key = "private"\nencrypt = true\n')
        self.data['terraform'] = dict(stateReference=str(backend), backendDirectory=str(self.root))
        self.data['stateMode'] = 'remote'
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root)
        content = bundle['files']['deploy/backend/backend.hcl'].decode()
        self.assertNotIn('private', content)
        self.assertIn('encrypt = true', content)

    def test_relative_windows_paths_and_absolute_local_module(self):
        self.data['localContext']['terraformDirectory'] = 'infra\\root'
        (self.root / 'infra/root/main.tf').write_text('module "child" { source = ' + json.dumps(str(self.root / 'infra/module')) + ' }')
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root)
        sources = [value for key, value in bundle['files'].items() if key.endswith('/root/main.tf')]
        self.assertIn(b'../module', sources[0])
        self.assertNotIn(str(self.root).encode(), sources[0])

    def test_active_pending_preserved_baselines_are_all_collected(self):
        self.data['deployment'] = {'activeReleaseBaseline': {'source': copy.deepcopy(self.data['source']), 'artifacts': copy.deepcopy(self.data['artifacts'])}}
        candidate = self.root / 'candidate-release'
        shutil.copytree(self.root / 'release', candidate)
        self.data['artifacts']['releaseDirectory'] = str(candidate)
        self.data['pendingReleaseBaseline'] = {'artifacts': copy.deepcopy(self.data['artifacts'])}
        preserved = self.root / 'preserved-release'
        shutil.copytree(candidate, preserved)
        self.data['preservedBaseline'] = {'artifacts': dict(self.data['artifacts'], releaseDirectory=str(preserved))}
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root)
        self.assertEqual(sum(key.endswith('/auto-wonder.jar') for key in bundle['files']), 3)

    def test_incomplete_skips_only_not_yet_created_files(self):
        (self.root / 'infra/root/terraform-secrets.env').unlink()
        (self.root / 'application.env').unlink()
        bundle_module.collect(self.manifest, self.root, allow_incomplete=True)
        (self.root / 'release/auto-wonder.jar').unlink()
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.collect(self.manifest, self.root, allow_incomplete=True)

    def test_terraform_template_input_and_workspace_state(self):
        tf = self.root / 'infra/root'
        (tf / 'startup.tftpl').write_text('boot')
        with (tf / 'main.tf').open('a') as stream:
            stream.write('\nlocals { script = templatefile("${path.module}/startup.tftpl", {}) }')
        state = tf / 'terraform.tfstate.d/blue/terraform.tfstate'
        state.parent.mkdir(parents=True)
        state.write_text('{"serial":99}')
        bundle = bundle_module.collect(self.manifest, self.root)
        self.assertTrue(any(key.endswith('/startup.tftpl') for key in bundle['files']))
        self.assertTrue(any(key.endswith('/terraform.tfstate.d/blue/terraform.tfstate') for key in bundle['files']))

    def test_restore_symlink_parent_rejected_without_write(self):
        bundle = bundle_module.collect(self.manifest, self.root)
        target = Path(self.temp.name) / 'target'
        target.mkdir()
        link = Path(self.temp.name) / 'link'
        link.symlink_to(target)
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.restore(bundle, link / 'nested')
        self.assertEqual(list(target.iterdir()), [])

    def test_bootstrap_empty_terraform_root(self):
        tf = self.root / 'infra/root'
        for path in tf.iterdir():
            path.unlink()
        self.data = dict(deploymentId='new', stateMode='remote', localContext={'terraformDirectory': str(tf)})
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root, allow_incomplete=True)
        self.assertFalse(bundle['manifest']['operationsBundle']['localStateRequiresMigration'])
        self.assertNotIn('ready', bundle['manifest'].get('status', ''))
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.collect(self.manifest, self.root)

    def test_windows_acl_is_set_before_writing_and_errors_are_sanitized(self):
        output = SimpleNamespace(returncode=0)
        with patch.object(bundle_module, 'os', SimpleNamespace(name='nt')), patch.object(bundle_module.subprocess, 'run', return_value=output) as run:
            bundle_module.protect_directory(self.root)
        command = run.call_args.args[0]
        self.assertEqual(command[:4], ['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand'])
        script = base64.b64decode(command[4]).decode('utf-16-le')
        self.assertIn('SetAccessRuleProtection($true,$false)', script)
        self.assertIn('WindowsIdentity]::GetCurrent().User', script)
        self.assertIn('DirectorySecurity', script)
        with patch.object(bundle_module, 'os', SimpleNamespace(name='nt')), patch.object(bundle_module.subprocess, 'run', return_value=SimpleNamespace(returncode=1, stderr='secret')):
            with self.assertRaisesRegex(bundle_module.BundleError, '^Cannot protect the recovery directory ACL$'):
                bundle_module.protect_directory(self.root)

    def test_private_write_is_atomic_and_private(self):
        path = self.root / 'protected/item'
        bundle_module.private_write(path, b'original')
        bundle_module.private_write(path, b'replaced')
        self.assertEqual(path.read_bytes(), b'replaced')
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(list(path.parent.iterdir()), [path])

    def test_remote_backend_does_not_collect_residual_local_state(self):
        tf = self.root / 'infra/root'
        (tf / 'terraform.tfstate.backup').write_text('old secret-bearing state')
        workspace = tf / 'terraform.tfstate.d/old/terraform.tfstate'
        workspace.parent.mkdir(parents=True)
        workspace.write_text('{}')
        self.data['stateMode'] = 'remote'
        self.data['terraform'] = {'stateMode': 'local', 'localStateFile': str(tf / 'terraform.tfstate')}
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root)
        self.assertFalse(bundle['manifest']['operationsBundle']['localStateRequiresMigration'])
        self.assertFalse(any('.tfstate' in name for name in bundle['files']))
        self.assertNotIn('localStateFile', bundle['manifest']['terraform'])
        self.assertTrue((tf / 'terraform.tfstate').is_file())

    def test_inline_backend_credentials_rejected_without_modifying_source(self):
        backend = self.root / 'infra/root/backend.tf'
        content = 'terraform { backend "oss" { bucket = "test" access_key = "temporary" secret_key = "private" } }'
        backend.write_text(content)
        for incomplete in (False, True):
            with self.assertRaisesRegex(bundle_module.BundleError, 'credential'):
                bundle_module.collect(self.manifest, self.root, allow_incomplete=incomplete)
        self.assertEqual(backend.read_text(), content)

    def test_json_backend_credentials_rejected(self):
        backend = self.root / 'infra/root/backend.tf.json'
        backend.write_text(json.dumps({'terraform': {'backend': {'oss': {'security_token': 'temporary'}}}}))
        with self.assertRaisesRegex(bundle_module.BundleError, 'credential'):
            bundle_module.collect(self.manifest, self.root)

    def test_collect_restore_collect_keeps_portable_files_and_path_metadata(self):
        first = bundle_module.collect(self.manifest, self.root)
        target = Path(self.temp.name) / 'restored'
        manifest = bundle_module.restore(first, target)
        shutil.rmtree(self.root)
        second = bundle_module.collect(manifest, target)
        self.assertEqual(first['files'], second['files'])
        self.assertEqual(first['manifest']['operationsBundle'], second['manifest']['operationsBundle'])
        third = bundle_module.restore(second, Path(self.temp.name) / 'restored-again')
        state = json.loads(third.read_text())
        self.assertTrue(Path(state['localContext']['protectedEnvFile']).is_file())
        self.assertTrue(Path(state['upgrade']['candidateEnvFile']).is_file())

    def test_restore_recreates_empty_bootstrap_terraform_directory(self):
        tf = self.root / 'empty-terraform'
        tf.mkdir()
        self.data = dict(deploymentId='new', stateMode='remote', localContext={'terraformDirectory': str(tf)})
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root, allow_incomplete=True)
        target = Path(self.temp.name) / 'bootstrap-restored'
        restored = bundle_module.restore(bundle, target)
        data = json.loads(restored.read_text())
        self.assertTrue(Path(data['localContext']['terraformDirectory']).is_dir())
        self.assertEqual(list(Path(data['localContext']['terraformDirectory']).iterdir()), [])
        bundle_module.collect(restored, target, allow_incomplete=True)

    def test_restore_empty_directory_rejects_symlink_and_path_traversal(self):
        tf = self.root / 'empty-terraform'
        tf.mkdir()
        self.data = dict(deploymentId='new', stateMode='remote', localContext={'terraformDirectory': str(tf)})
        self.save()
        bundle = bundle_module.collect(self.manifest, self.root, allow_incomplete=True)
        relative = bundle['manifest']['localContext']['terraformDirectory']
        target = Path(self.temp.name) / 'bootstrap-restored'
        link = target / relative
        link.parent.mkdir(parents=True)
        link.symlink_to(self.root)
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.restore(bundle, target)
        link.unlink()
        bundle['manifest']['operationsBundle']['pathFields'][0]['path'] = '../escape'
        with self.assertRaises(bundle_module.BundleError):
            bundle_module.restore(bundle, target)
        self.assertFalse((target / 'manifest.json').exists())


if __name__ == '__main__':
    unittest.main()
