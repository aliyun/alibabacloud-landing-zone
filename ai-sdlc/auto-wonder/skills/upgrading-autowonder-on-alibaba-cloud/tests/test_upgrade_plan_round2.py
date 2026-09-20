import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / 'scripts/upgrade_plan.py'
spec = importlib.util.spec_from_file_location('upgrade_plan_round2', HELPER)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class WorkspaceIdentityTest(unittest.TestCase):
    def test_posix_builder_uses_same_workspace_identity_without_git_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'VERSION').write_text('0.5.0\n')
            source_identity = policy.content_identity(root)
            (root / '.git').write_text('gitdir: /private/repo/.git/worktrees/release\n')
            result = subprocess.run(
                ['bash', '-c', 'source "$1"; workspace_content_identity "$2"',
                 'bash', str(HELPER.with_name('upgrade-lib.sh')), str(root)],
                text=True, capture_output=True, check=True,
            )
            self.assertEqual(source_identity, result.stdout.strip())

    def test_operations_state_does_not_change_workspace_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'src' / 'application.py'
            source.parent.mkdir()
            source.write_text('version = 1\n')
            identity = policy.content_identity(root)
            for name in ('.operations-cache', 'deployments'):
                state = root / name / 'deployment' / 'protected.env'
                state.parent.mkdir(parents=True)
                state.write_text('SECRET=first\n')
                self.assertEqual(identity, policy.content_identity(root))
                state.write_text('SECRET=second\n')
                self.assertEqual(identity, policy.content_identity(root))
            result = subprocess.run(
                ['bash', '-c', 'source "$1"; workspace_content_identity "$2"',
                 'bash', str(HELPER.with_name('upgrade-lib.sh')), str(root)],
                text=True, capture_output=True, check=True,
            )
            self.assertEqual(identity, result.stdout.strip())
            source.write_text('version = 2\n')
            self.assertNotEqual(identity, policy.content_identity(root))

    def test_only_root_operations_directories_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('.operations-cache', 'deployments'):
                source = root / 'src' / name / 'handler.py'
                source.parent.mkdir(parents=True)
                source.write_text('version = 1\n')
                identity = policy.content_identity(root)
                source.write_text('version = 2\n')
                self.assertNotEqual(identity, policy.content_identity(root))

    def test_frontend_generated_files_do_not_change_workspace_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'frontend/src/app.ts'
            source.parent.mkdir(parents=True)
            source.write_text('export const version = 1;\n')
            identity = policy.content_identity(root)
            for generated in ('frontend/tsconfig.tsbuildinfo', 'frontend/src/tsconfig.tsbuildinfo',
                              'frontend/coverage/report.json', 'frontend/dist/app.js'):
                path = root / generated
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('generated build output\n')
                self.assertEqual(identity, policy.content_identity(root), generated)
                path.write_text('changed build output\n')
                self.assertEqual(identity, policy.content_identity(root), generated)
            result = subprocess.run(
                ['bash', '-c', 'source "$1"; workspace_content_identity "$2"',
                 'bash', str(HELPER.with_name('upgrade-lib.sh')), str(root)],
                text=True, capture_output=True, check=True,
            )
            self.assertEqual(identity, result.stdout.strip())
            source.write_text('export const version = 2;\n')
            self.assertNotEqual(identity, policy.content_identity(root))

    def test_tsbuildinfo_outside_frontend_remains_in_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'server.tsbuildinfo'
            source.write_text('first\n')
            identity = policy.content_identity(root)
            source.write_text('second\n')
            self.assertNotEqual(identity, policy.content_identity(root))

    def test_root_runtime_logs_do_not_change_workspace_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'src/logs/handler.py'
            source.parent.mkdir(parents=True)
            source.write_text('version = 1\n')
            identity = policy.content_identity(root)
            runtime_log = root / 'logs/auto-wonder.log'
            runtime_log.parent.mkdir()
            runtime_log.write_text('first test run\n')
            self.assertEqual(identity, policy.content_identity(root))
            runtime_log.write_text('second test run\n')
            self.assertEqual(identity, policy.content_identity(root))
            result = subprocess.run(
                ['bash', '-c', 'source "$1"; workspace_content_identity "$2"',
                 'bash', str(HELPER.with_name('upgrade-lib.sh')), str(root)],
                text=True, capture_output=True, check=True,
            )
            self.assertEqual(identity, result.stdout.strip())
            source.write_text('version = 2\n')
            self.assertNotEqual(identity, policy.content_identity(root))

    def test_worktree_git_pointer_does_not_change_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'VERSION').write_text('0.5.0\n')
            source_identity = policy.content_identity(root)
            pointer = root / '.git'
            pointer.write_text('gitdir: /private/first/.git/worktrees/release\n')
            self.assertEqual(source_identity, policy.content_identity(root))
            pointer.write_text('gitdir: /private/second/.git/worktrees/release\n')
            self.assertEqual(source_identity, policy.content_identity(root))
            (root / 'VERSION').write_text('0.5.1\n')
            self.assertNotEqual(source_identity, policy.content_identity(root))


if __name__ == '__main__':
    unittest.main()
