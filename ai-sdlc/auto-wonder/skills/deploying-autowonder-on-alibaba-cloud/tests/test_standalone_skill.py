"""A deployment Skill must work without an installed upgrade Skill."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SKILL = Path(__file__).resolve().parents[1]


class StandaloneDeploymentTests(unittest.TestCase):
    def test_state_recovery_and_baseline_load_without_sibling(self):
        with tempfile.TemporaryDirectory(prefix='aw standalone 中文 ') as directory:
            root = Path(directory)
            scripts = root / 'skills' / SKILL.name / 'scripts'
            shutil.copytree(SKILL, scripts.parent)
            env = dict(os.environ, PYTHONPATH=str(scripts), PYTHONDONTWRITEBYTECODE='1')
            probe = '''
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('state',Path('operations-store.py'))
state=importlib.util.module_from_spec(spec)
spec.loader.exec_module(state)
assert state.upgrade_info().canonical_json({}) == b'{}\\n'
import upgrade_plan
assert upgrade_plan.content_identity(Path.cwd())
'''
            result = subprocess.run([sys.executable, '-B', '-c', probe], cwd=scripts,
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_isolated_build_seal_and_oss_recovery_fixtures(self):
        with tempfile.TemporaryDirectory(prefix='aw isolated deployment ') as directory:
            skill = Path(directory) / 'skills' / SKILL.name
            shutil.copytree(SKILL, skill)
            env = dict(os.environ, PYTHONPATH=str(skill / 'scripts'),
                       PYTHONDONTWRITEBYTECODE='1', GIT_CONFIG_COUNT='1',
                       GIT_CONFIG_KEY_0='core.hooksPath', GIT_CONFIG_VALUE_0=os.devnull)
            result = subprocess.run([
                sys.executable, '-B', '-m', 'unittest',
                'test_script_contracts.ScriptContracts.test_build_release_accepts_monorepo_project_subdirectory',
                'test_operations_store.OperationsStoreTests.test_delete_local_directories_and_restore_full_working_manifest',
                'test_operations_store.OperationsStoreTests.test_import_keeps_newer_upgrade_state_and_original_deployment_inputs',
            ], cwd=skill / 'tests', env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_no_runtime_path_to_sibling_upgrade_skill(self):
        for path in (SKILL / 'scripts').rglob('*'):
            if path.suffix in ('.py', '.sh', '.ps1', '.cmd'):
                text = path.read_text()
                self.assertFalse('/../../upgrading-autowonder-on-alibaba-cloud' in text, str(path))
                self.assertNotIn('/../../../upgrading-autowonder-on-alibaba-cloud', text, str(path))
                self.assertNotIn("SCRIPTS.parent.parent / 'upgrading-autowonder-on-alibaba-cloud'", text, str(path))
                self.assertNotIn('..\\..\\..\\upgrading-autowonder-on-alibaba-cloud', text, str(path))


if __name__ == '__main__':
    unittest.main()
