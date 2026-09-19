"""Identical bundled primitives must not drift; standalone skills keep local copies."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'skills'

class BundledPrimitiveTests(unittest.TestCase):
    def test_shared_primitives_match(self):
        for name in ('cloud_diagnostics.py', 'ecs_inventory.py', 'operation_metrics.py',
                     'operations_hooks.py', 'lib.sh', 'windows/lib.ps1'):
            with self.subTest(module=name):
                a = ROOT / 'deploying-autowonder-on-alibaba-cloud/scripts' / name
                b = ROOT / 'upgrading-autowonder-on-alibaba-cloud/scripts' / name
                self.assertTrue(a.is_file() and b.is_file(), 'Both standalone bundles require ' + name)
                if a.is_file() and b.is_file():self.assertEqual(a.read_bytes(), b.read_bytes())
