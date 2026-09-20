"""Offline producer/recovery and legacy remote snapshot regressions."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

import test_windows_upgrade_execution as execution

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


class LegacyBackupTests(unittest.TestCase):
    setUp = execution.WindowsUpgradeExecutionTests.setUp
    tearDown = execution.WindowsUpgradeExecutionTests.tearDown
    run_payload = execution.WindowsUpgradeExecutionTests.run_payload
    mock_services = execution.WindowsUpgradeExecutionTests.mock_services

    def legacy(self, plan=None, corrupt=False):
        snapshot = self.root / 'legacy'
        shutil.copytree(self.app / 'releases' / self.old, snapshot / 'release')
        (snapshot / 'autowonder.env').write_text('ORIGINAL=protected\n')
        (snapshot / 'autowonder.service').write_text('original unit')
        (snapshot / 'plan-fingerprint').write_text((plan or self.plan) + '\n')
        (snapshot / 'release-name').write_text(self.old + '\n')
        checks = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ./' + p.relative_to(snapshot).as_posix() + '\n'
                         for p in sorted(snapshot.rglob('*')) if p.is_file())
        (snapshot / 'CHECKSUMS').write_text(checks)
        if corrupt:
            (snapshot / 'autowonder.env').write_text('corrupt')
        archive = self.app / 'upgrade-rollback-backup.tar.gz'
        with tarfile.open(archive, 'w:gz') as package:
            package.add(snapshot, arcname='.')
        return archive

    def test_same_plan_posix_backup_reused_without_resnapshotting(self):
        archive = self.legacy()
        original = archive.read_bytes()
        (self.config / 'autowonder.env').write_text('staged config')
        result = self.run_payload('upgrade-backup.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(original, archive.read_bytes())
        self.mock_services()
        result = self.run_payload('rollback-upgrade.sh', {'backupSha': hashlib.sha256(original).hexdigest()}, mock_system=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('ORIGINAL=protected\n', (self.config / 'autowonder.env').read_text())

    def test_new_plan_validates_legacy_archive_then_creates_current_snapshot(self):
        archive = self.legacy(plan='d' * 64)
        (self.config / 'autowonder.env').write_text('CURRENT=value\n')
        result = self.run_payload('upgrade-backup.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        with tarfile.open(archive) as package:
            self.assertEqual(b'CURRENT=value\n', package.extractfile('autowonder.env').read())
            self.assertEqual((self.plan + '\n').encode(), package.extractfile('./plan-fingerprint').read())

    def test_corrupt_legacy_archive_is_preserved_even_for_new_plan(self):
        archive = self.legacy(plan='d' * 64, corrupt=True)
        original = archive.read_bytes()
        result = self.run_payload('upgrade-backup.sh')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(original, archive.read_bytes())
        self.assertIn('checksum mismatch', result.stderr)


class WindowsManifestRecoveryTests(unittest.TestCase):
    def test_build_replaces_old_descriptors_and_restores_every_artifact(self):
        for old_complete in (False, True):
            with self.subTest(old_complete=old_complete), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = root / 'source'
                source.mkdir()
                (source / 'VERSION').write_text('0.9.0\n')
                release = root / 'release'
                release.mkdir()
                artifacts = {}
                for name in ('auto-wonder.jar', 'autowonder-schema.sql', 'autowonder-community-templates.sql', 'autowonder-migrations.tar.gz', 'autowonder.service'):
                    content = ('new ' + name).encode()
                    (release / name).write_bytes(content)
                    artifacts[name] = {'sha256': hashlib.sha256(content).hexdigest(), 'size': len(content), 'source': 'target-source'}
                build = {'directory': str(release), 'artifacts': artifacts}
                tf = root / 'terraform'
                tf.mkdir()
                (tf / 'main.tf').write_text('terraform {}')
                (tf / 'terraform.tfstate').write_text('{}')
                (tf / 'terraform-secrets.env').write_text('TF_VAR_password=fixture')
                (tf / 'deployment.auto.tfvars.json').write_text('{}')
                env = root / 'application.env'
                env.write_text('KEY=fixture')
                manifest = root / 'manifest.json'
                data = {'releaseVersion': '0.8.0', 'deployment': {'activeCommit': 'a' * 40}, 'stateMode': 'local',
                        'localContext': {'terraformDirectory': str(tf), 'protectedEnvFile': str(env), 'activeEnvFile': str(env), 'previousActiveEnvFile': str(env), 'candidateEnvFile': str(env)},
                        'upgrade': {'toCommit': 'b' * 40, 'planFingerprint': 'c' * 64},
                        'artifacts': {'releaseDirectory': str(release), 'jar': {}, 'migrations': {}}}
                if old_complete:
                    data['artifacts'].update(schema={'name': 'autowonder-schema.sql', 'sha256': '0'*64}, templates={'name': 'autowonder-community-templates.sql', 'sha256': '0'*64})
                result = subprocess.run([sys.executable, str(SCRIPTS / 'windows_release.py'), '--source-dir', str(source)], input=json.dumps(build), capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                patch = json.loads(result.stdout)
                data.update(patch)
                data['upgrade']['release'] = {'directory': str(release), 'artifacts': artifacts, 'commit': 'b' * 40}
                manifest.write_text(json.dumps(data))
                self.assertEqual('0.9.0', data['releaseVersion'])
                self.assertEqual('target-source', data['artifacts']['systemdUnit']['source'])
                self.assertEqual('a' * 40, data['deployment']['activeCommit'])
                for skill in ('upgrading', 'deploying'):
                    module_path = SCRIPTS.parents[1] / (skill + '-autowonder-on-alibaba-cloud/scripts/operations_bundle.py')
                    spec = importlib.util.spec_from_file_location('bundle_' + skill, module_path)
                    module = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(module)
                    bundle = module.collect(manifest, root)
                    recovered_path = module.restore(bundle, root / ('recovered-' + skill))
                    recovered = json.loads(recovered_path.read_text())
                    recovered_release = Path(recovered['artifacts']['releaseDirectory'])
                    for key in ('activeEnvFile', 'candidateEnvFile', 'previousActiveEnvFile'):
                        self.assertEqual('KEY=fixture', Path(recovered['localContext'][key]).read_text())
                        self.assertNotEqual(str(env), recovered['localContext'][key])
                    self.assertEqual(set(artifacts), {p.name for p in recovered_release.iterdir()})
                    self.assertEqual(str(recovered_release), recovered['upgrade']['release']['releaseDirectory'])
                    self.assertNotIn('directory', recovered['upgrade']['release'])
                    for name in artifacts:
                        self.assertEqual((release / name).read_bytes(), (recovered_release / name).read_bytes())


if __name__ == '__main__':
    unittest.main()
