import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/prepare-upgrade-source.py'


class SourcePreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'source'
        self.repo.mkdir()
        def git(*args):
            subprocess.run(['git', '-C', str(self.repo), *args], check=True, capture_output=True)
        git('init', '-b', 'main')
        git('config', 'core.hooksPath', '/dev/null')
        git('config', 'user.email', 'fixture@example.invalid')
        git('config', 'user.name', 'fixture')
        for name in ('VERSION', 'pom.xml', 'src/main/resources/application.yml'):
            p = self.repo / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('fixture')
        git('add', '.')
        git('commit', '-m', 'fixture')
        self.manifest = self.root / 'manifest.json'
        self.manifest.write_text(json.dumps({'deploymentId': 'locked-target', 'repositoryUrl': str(self.repo)}))

    def run_source(self, *extra):
        import sys
        return subprocess.run([sys.executable, str(SCRIPT), '--manifest', str(self.manifest),
                               '--workspace', str(self.root / 'new computer'), '--deployment-id',
                               'locked-target', *extra], capture_output=True, text=True)

    def test_new_directory_prepares_detached_default_branch_without_touching_original(self):
        result = self.run_source()
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual('main', report['targetRef'])
        self.assertEqual('locked-target', report['deploymentId'])
        branch = subprocess.check_output(['git', '-C', report['sourceDirectory'], 'branch', '--show-current'], text=True)
        self.assertEqual('', branch.strip())
        self.assertEqual('main', subprocess.check_output(['git', '-C', str(self.repo), 'branch', '--show-current'], text=True).strip())

    def test_wrong_deployment_is_rejected_before_source_creation(self):
        self.manifest.write_text(json.dumps({'deploymentId': 'other', 'repositoryUrl': str(self.repo)}))
        result = self.run_source()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('deployment', result.stderr.lower())
        self.assertFalse((self.root / 'new computer').exists())

    def test_source_change_requires_explicit_authorization(self):
        self.manifest.write_text(json.dumps({'deploymentId': 'locked-target', 'repositoryUrl': 'https://example.invalid/old.git'}))
        result = self.run_source('--repository-url', str(self.repo))
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('Traceback', result.stderr)
        result = self.run_source('--repository-url', str(self.repo), '--allow-repository-change')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(json.loads(result.stdout)['repositoryChanged'])
        self.assertEqual('https://example.invalid/old.git', json.loads(self.manifest.read_text())['repositoryUrl'])
