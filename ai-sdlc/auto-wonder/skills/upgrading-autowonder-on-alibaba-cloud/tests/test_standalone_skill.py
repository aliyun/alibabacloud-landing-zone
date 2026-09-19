"""Exercise the upgrade bundle with no deployment Skill present."""
import os
import json
import zipfile
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class StandaloneUpgradeTests(unittest.TestCase):
    def test_isolated_bundle_imports_core_and_opens_public_entrypoints(self):
        with tempfile.TemporaryDirectory(prefix='升级 独立 ') as folder:
            project = Path(folder)
            bundle = project / 'skills' / ROOT.name
            shutil.copytree(ROOT, bundle, ignore=shutil.ignore_patterns('__pycache__'))
            (project / 'pom.xml').write_text('<project/>')
            self.assertFalse((project / 'skills/deploying-autowonder-on-alibaba-cloud').exists())
            scripts = bundle / 'scripts'
            env = dict(os.environ, PYTHONPATH='', AUTOWONDER_PYTHON=sys.executable,
                       PYTHONDONTWRITEBYTECODE='1')
            for name in ('upgrade_plan.py', 'upgrade_info.py', 'operations-store.py', 'tool_runtime.py'):
                with self.subTest(entrypoint=name):
                    result = subprocess.run([sys.executable, str(scripts / name), '--help'],
                        cwd=project, env=env, capture_output=True, text=True, timeout=15)
                    self.assertEqual(result.returncode, 0, result.stderr)
            fixture_env = dict(env, GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='core.hooksPath', GIT_CONFIG_VALUE_0=os.devnull)
            tests = ['test_operations_restore']
            if shutil.which('bash'):
                tests.extend(['test_upgrade_plan.UpgradePlanTest.test_workspace_same_version_identity_matches_build_without_git',
                              'test_upgrade_plan.UpgradePlanTest.test_sealing_persists_source_contract_bound_to_release_identity'])
            checked = subprocess.run([sys.executable, '-B', '-m', 'unittest', *tests],
                cwd=bundle / 'tests', env=fixture_env, capture_output=True, text=True, timeout=90)
            self.assertEqual(checked.returncode, 0, checked.stderr)
            if shutil.which('bash'):
                for name in ('build-upgrade-release.sh', 'plan-upgrade.sh', 'resolve-deployment.sh',
                             'refresh-upgrade-info.sh', 'verify-deployment-targets.sh',
                             'bootstrap-control-host.sh', 'runtime-bootstrap.sh'):
                    with self.subTest(entrypoint=name):
                        result = subprocess.run(['bash', str(scripts / name), '--help'],
                            cwd=project, env=env, capture_output=True, text=True, timeout=15)
                        self.assertEqual(result.returncode, 0, result.stderr)


    @unittest.skipIf(os.name == 'nt', 'POSIX fake Maven entrypoint')
    def test_isolated_bundle_builds_with_its_own_systemd_and_fingerprints_only_its_skill(self):
        with tempfile.TemporaryDirectory(prefix='升级 构建 ') as folder:
            project = Path(folder) / 'project'
            bundle = project / 'skills' / ROOT.name
            shutil.copytree(ROOT, bundle, ignore=shutil.ignore_patterns('__pycache__'))
            scripts = bundle / 'scripts'
            for name in ('target', 'docs/migration', 'src/main/resources'):
                (project / name).mkdir(parents=True, exist_ok=True)
            (project / 'pom.xml').write_text('<project/>')
            (project / 'VERSION').write_text('1.0.0')
            (project / 'src/main/resources/application.yml').write_text('test: true')
            for name in ('autowonder-schema.sql', 'autowonder-community-templates.sql'):
                (project / 'docs' / name).write_text('SELECT 1;')
            (project / 'docs/migration/V1__fixture.sql').write_text('SELECT 1;')
            with zipfile.ZipFile(project / 'target/auto-wonder.jar', 'w') as jar:
                jar.writestr('BOOT-INF/classes/static/index.html', '<html/>')
                jar.writestr('BOOT-INF/classes/static/assets/app.js', 'fixture')
            env = dict(os.environ, PYTHONPATH='', PYTHONDONTWRITEBYTECODE='1')
            identity = [sys.executable, str(scripts / 'upgrade_plan.py'), 'content-identity', '--source-dir', str(project)]
            before = subprocess.check_output(identity, env=env, text=True)
            sibling = project / 'skills' / 'unrelated-skill'
            sibling.mkdir()
            (sibling / 'private.py').write_text('must not enter the upgrade fingerprint')
            self.assertEqual(before, subprocess.check_output(identity, env=env, text=True))
            binary = Path(folder) / 'bin'
            binary.mkdir()
            (binary / 'mvn').write_text('#!/bin/sh\nexit 0\n')
            (binary / 'mvn').chmod(0o700)
            env['PATH'] = str(binary) + os.pathsep + env['PATH']
            result = subprocess.run([sys.executable, str(scripts / 'release_build.py'),
                '--source-dir', str(project), '--output-dir', str(Path(folder) / 'release'), '--include-unit'],
                env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            release = Path(json.loads(result.stdout)['directory'])
            self.assertEqual((release / 'autowonder.service').read_bytes(),
                             (bundle / 'assets/systemd/autowonder.service').read_bytes())
            # The executing bundle can differ from the approved target tree.
            runner = Path(folder) / 'runner'
            (runner / 'scripts').mkdir(parents=True)
            (runner / 'assets/systemd').mkdir(parents=True)
            shutil.copyfile(scripts / 'release_build.py', runner / 'scripts/release_build.py')
            (runner / 'assets/systemd/autowonder.service').write_text('WRONG EXECUTING VERSION')
            result = subprocess.run([sys.executable, str(runner / 'scripts/release_build.py'),
                '--source-dir', str(project), '--output-dir', str(release), '--include-unit'],
                env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((release / 'autowonder.service').read_bytes(),
                             (bundle / 'assets/systemd/autowonder.service').read_bytes())
            manifest = Path(folder) / 'unknown.json'
            manifest.write_text(json.dumps({'operationsStore': {}, 'remoteSubmission': {'status': 'unknown'}}))
            gate = subprocess.run([sys.executable, str(scripts / 'cloud_assistant.py'),
                'assert-settled', '--manifest', str(manifest)], env=env, capture_output=True)
            self.assertNotEqual(gate.returncode, 0)

    def test_executable_bundle_has_no_other_skill_paths(self):
        forbidden = 'deploying-autowonder-on-alibaba-cloud'
        for root in (ROOT / 'scripts', ROOT / 'assets'):
            if root.exists():
                for path in root.rglob('*'):
                    if path.is_file() and path.suffix in ('.py', '.sh', '.ps1', '.cmd', '.service', '.json'):
                        with self.subTest(path=path.relative_to(ROOT)):
                            self.assertFalse(forbidden in path.read_text(encoding='utf-8'), str(path))

if __name__ == '__main__':
    unittest.main()
