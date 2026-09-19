import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.find_spec('release_build')
if SPEC:
    import release_build


class ReleaseBuildTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(SPEC, 'shared release build core must exist')
        self.temp = tempfile.TemporaryDirectory(prefix='构建 空格 ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'project'
        self.output = self.root / 'sealed'
        for name in ['target', 'docs/migration', 'skills/deploying-autowonder-on-alibaba-cloud/assets/systemd']:
            (self.source / name).mkdir(parents=True)
        for name in ['autowonder-schema.sql', 'autowonder-community-templates.sql']:
            (self.source / 'docs' / name).write_text('SELECT 1;\n')
        (self.source / 'docs/migration/V1__test.sql').write_text('SELECT 2;\n')
        (self.source / 'skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/autowonder.service').write_text('[Service]\n')
        self.create_jar()

    def create_jar(self, index=True, assets=True):
        with zipfile.ZipFile(self.source / 'target/auto-wonder.jar', 'w') as jar:
            jar.writestr('BOOT-INF/classes/application.yml', 'test: true\n')
            if index:
                jar.writestr('BOOT-INF/classes/static/index.html', '<html>test</html>')
            if assets:
                jar.writestr('BOOT-INF/classes/static/assets/app.js', 'test')

    def fake_maven(self, command, **kwargs):
        self.assertEqual(command[-2:], ['clean', 'package'])
        self.assertIn('-DskipFrontend=false', command)
        self.assertIn('-Dmaven.test.skip=true', command)
        self.assertEqual(Path(kwargs['cwd']), self.source.resolve())
        class Result:
            returncode = 0
        return Result()

    def build(self, **kwargs):
        with patch.object(release_build.subprocess, 'run', side_effect=self.fake_maven):
            return release_build.build_release(self.source, self.output, **kwargs)

    def test_standalone_upgrade_build_also_skips_application_tests(self):
        path = SCRIPTS.parents[1] / 'upgrading-autowonder-on-alibaba-cloud/scripts/release_build.py'
        spec = importlib.util.spec_from_file_location('upgrade_release_build_fixture', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with patch.object(module.subprocess, 'run', side_effect=self.fake_maven):
            result = module.build_release(self.source, self.output)
        self.assertIn('auto-wonder.jar', result['artifacts'])

    def test_verify_frontend_archive_root_and_target_unit(self):
        result = self.build(include_unit=True)
        self.assertEqual(result['artifacts']['auto-wonder.jar']['sha256'],
                         hashlib.sha256((self.output / 'auto-wonder.jar').read_bytes()).hexdigest())
        self.assertEqual((self.output / 'autowonder.service').read_text(), '[Service]\n')
        with tarfile.open(self.output / 'autowonder-migrations.tar.gz') as archive:
            self.assertEqual(archive.extractfile('./V1__test.sql').read(), b'SELECT 2;\n')
            self.assertNotIn('migration/V1__test.sql', archive.getnames())
        if os.name != 'nt':
            self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE((self.output / 'auto-wonder.jar').stat().st_mode), 0o444)

    def test_invalid_or_incomplete_jar_cannot_replace_release(self):
        for index, assets in [(False, True), (True, False)]:
            self.create_jar(index=index, assets=assets)
            with self.subTest(index=index, assets=assets), self.assertRaises(ValueError):
                self.build()
            self.assertFalse(self.output.exists())
        (self.source / 'target/auto-wonder.jar').write_bytes(b'not a zip')
        with self.assertRaises(ValueError):
            self.build()

    def test_readonly_release_can_be_rebuilt(self):
        self.build()
        self.create_jar()
        self.build()
        self.assertEqual(list(self.output.glob('.*tmp*')), [])
        self.assertTrue((self.output / 'auto-wonder.jar').is_file())

    def test_maven_resolves_native_batch_entrypoint(self):
        observed = []
        def fake(command, **kwargs):
            observed.append(command[0])
            return self.fake_maven(command, **kwargs)
        with patch.object(release_build.shutil, 'which', return_value='/tools with spaces/mvn.cmd'), \
                patch.object(release_build.subprocess, 'run', side_effect=fake):
            release_build.build_release(self.source, self.output)
        self.assertEqual(observed, ['/tools with spaces/mvn.cmd'])

    def test_failed_maven_cannot_seal_existing_stale_jar(self):
        with patch.object(release_build.subprocess, 'run') as run:
            run.return_value.returncode = 7
            with self.assertRaises(ValueError):
                release_build.build_release(self.source, self.output)
        self.assertFalse(self.output.exists())

    @unittest.skipIf(os.name == 'nt', 'POSIX wrapper requires a POSIX host')
    def test_posix_wrapper_rejects_source_or_plan_changes_during_build(self):
        (self.source / 'VERSION').write_text('1.2.3\n')
        (self.source / 'README.md').write_text('original\n')
        (self.source / 'src/main/resources').mkdir(parents=True)
        application = 'autowonder:\n  runtime:\n    recommended-version: 1.2.3\n'
        (self.source / 'src/main/resources/application.yml').write_text(application)
        # Baseline config in JAR must equal source, so a README-only mutation
        # specifically exercises the post-build identity gate rather than sealing.
        with zipfile.ZipFile(self.source / 'target/auto-wonder.jar', 'w') as jar:
            jar.writestr('BOOT-INF/classes/application.yml', application)
            jar.writestr('BOOT-INF/classes/static/index.html', '<html/>')
            jar.writestr('BOOT-INF/classes/static/assets/app.js', 'test')
        for command in [['init'], ['config', 'user.email', 'fixture@example.invalid'],
                        ['config', 'user.name', 'Fixture'], ['add', '.'], ['commit', '-m', 'fixture']]:
            subprocess.run(['git', '-C', str(self.source), *command], check=True, capture_output=True)
        commit = subprocess.check_output(['git', '-C', str(self.source), 'rev-parse', 'HEAD'], text=True).strip()
        manifest = self.root / 'manifest.json'
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        fake = bin_dir / 'mvn'
        env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'], AUTOWONDER_PYTHON=sys.executable)
        for mutation in ['source', 'plan']:
            with self.subTest(mutation=mutation):
                (self.source / 'README.md').write_text('original\n')
                manifest.write_text(json.dumps({'mode': 'upgrade', 'repositoryCommit': commit,
                                                'upgrade': {'planFingerprint': 'before'}}))
                action = (f'Path({str(self.source / "README.md")!r}).write_text("changed")' if mutation == 'source' else
                          f'p=Path({str(manifest)!r}); d=json.loads(p.read_text()); d["upgrade"]["planFingerprint"]="after"; p.write_text(json.dumps(d))')
                fake.write_text('#!' + sys.executable + '\nimport json\nfrom pathlib import Path\n' + action + '\n')
                fake.chmod(0o755)
                result = subprocess.run(['bash', str(SCRIPTS / 'build-release.sh'), '--manifest', str(manifest),
                                         '--source-dir', str(self.source), '--output-dir', str(self.output)],
                                        capture_output=True, text=True, env=env)
                self.assertNotEqual(result.returncode, 0, 'changed source/plan was incorrectly sealed')
                self.assertNotIn('baseline', json.loads(manifest.read_text()).get('source', {}))

    def test_migration_symlink_cannot_escape_source(self):
        (self.root / 'secret.sql').write_text('private')
        (self.source / 'docs/migration/V2__escape.sql').symlink_to(self.root / 'secret.sql')
        with self.assertRaises(ValueError):
            self.build()


if __name__ == '__main__':
    unittest.main()
