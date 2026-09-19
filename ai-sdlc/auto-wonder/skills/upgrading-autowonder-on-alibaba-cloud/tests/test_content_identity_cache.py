import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    'upgrade_plan', Path(__file__).resolve().parents[1] / 'scripts' / 'upgrade_plan.py')
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


class RuntimeCacheIdentityTest(unittest.TestCase):
    def test_only_project_runtime_cache_is_excluded(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'VERSION').write_text('0.8.0')
            original = plan.content_identity(root)
            cache = root / 'skills' / '.autowonder-tools'
            cache.mkdir(parents=True)
            (cache / 'python').write_bytes(b'cached runtime')
            self.assertEqual(original, plan.content_identity(root))
            (cache / 'python').write_bytes(b'new runtime')
            self.assertEqual(original, plan.content_identity(root))
            other = root / 'application' / '.autowonder-tools'
            other.mkdir(parents=True)
            (other / 'source.py').write_text('application source')
            self.assertNotEqual(original, plan.content_identity(root))

    def test_runtime_lock_and_core_remain_fingerprinted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            core = root / 'skills' / 'upgrading-autowonder-on-alibaba-cloud' / 'scripts'
            core.mkdir(parents=True)
            original = plan.content_identity(root)
            (core / 'runtime-lock.tsv').write_text('reviewed lock')
            locked = plan.content_identity(root)
            self.assertNotEqual(original, locked)
            (core / 'tool_runtime.py').write_text('shared implementation')
            self.assertNotEqual(locked, plan.content_identity(root))
