"""Offline checks for opt-in, private timing without replaying operations."""
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


class OperationMetricsTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((SCRIPTS / 'operation_metrics.py').exists(), 'opt-in timing helper is missing')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.target = self.root / 'metrics.jsonl'
        sys.path.insert(0, str(SCRIPTS))
        self.addCleanup(lambda: sys.path.remove(str(SCRIPTS)))
        import operation_metrics
        self.metrics = operation_metrics
        self.environment = mock.patch.dict(os.environ, {'AUTOWONDER_METRICS_FILE': str(self.target), 'AUTOWONDER_METRICS_PHASE': ''})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def records(self):
        return [json.loads(line) for line in self.target.read_text().splitlines()]

    def test_windows_optional_metrics_skip_preserves_success_and_failure(self):
        with mock.patch.object(self.metrics.os, 'name', 'nt'), contextlib.redirect_stderr(io.StringIO()):
            with self.metrics.measure('checkpoint'):
                result = 'succeeded'
            error = RuntimeError('original')
            with self.assertRaises(RuntimeError) as caught:
                with self.metrics.measure('checkpoint'):
                    raise error
        self.assertEqual('succeeded', result)
        self.assertIs(error, caught.exception)
        self.assertFalse(self.target.exists())

    def test_disabled_metrics_create_nothing_and_do_not_read_clock(self):
        with mock.patch.dict(os.environ, {'AUTOWONDER_METRICS_FILE': ''}), mock.patch.object(
                self.metrics.time, 'monotonic', side_effect=AssertionError('disabled metrics read clock')):
            with self.metrics.measure('checkpoint') as counts:
                counts['files'] = 2
        self.assertEqual([], list(self.root.iterdir()))

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_private_success_has_fixed_bounded_metadata_and_monotonic_elapsed(self):
        with mock.patch.object(self.metrics.time, 'monotonic', side_effect=[100.0, 102.25]):
            with self.metrics.measure('bundle_collect') as counts:
                counts.update(files=2, bytes=14, secret='DO-NOT-LOG', key='private/key')
        self.assertEqual([{'stage': 'bundle_collect', 'elapsed_seconds': 2.25,
                           'status': 'success', 'counts': {'files': 2, 'bytes': 14}}], self.records())
        if os.name != 'nt':
            self.assertEqual(0o600, stat.S_IMODE(self.target.stat().st_mode))

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_failure_logs_no_exception_text_and_preserves_exception_identity(self):
        error = RuntimeError('SECRET-REMOTE-OUTPUT')
        with self.assertRaises(RuntimeError) as caught:
            with self.metrics.measure('restore'):
                raise error
        self.assertIs(error, caught.exception)
        self.assertEqual('failure', self.records()[0]['status'])
        self.assertNotIn('SECRET', self.target.read_text())

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_unsafe_file_never_changes_and_does_not_fail_successful_operation(self):
        outside = self.root / 'outside'
        outside.write_text('unchanged')
        outside.chmod(0o600)
        for kind in ('symlink', 'parent_symlink', 'public', 'hardlink', 'fifo'):
            with self.subTest(kind=kind):
                target = self.root / kind
                if kind == 'symlink':
                    target.symlink_to(outside)
                elif kind == 'parent_symlink':
                    target.symlink_to(self.root, target_is_directory=True)
                    target = target / 'outside'
                elif kind == 'public':
                    target.write_text('unchanged')
                    target.chmod(0o644)
                elif kind == 'hardlink':
                    os.link(outside, target)
                else:
                    if not hasattr(os, 'mkfifo'):
                        continue
                    os.mkfifo(target)
                warning = io.StringIO()
                with mock.patch.dict(os.environ, {'AUTOWONDER_METRICS_FILE': str(target)}), contextlib.redirect_stderr(warning):
                    with self.metrics.measure('checkpoint'):
                        result = 'mutation succeeded'
                self.assertEqual('mutation succeeded', result)
                self.assertEqual('unchanged', outside.read_text())
                self.assertIn('metrics', warning.getvalue().lower())
                self.assertNotIn(str(target), warning.getvalue())
                if kind == 'public':
                    self.assertEqual('unchanged', target.read_text())

    def test_logging_failure_preserves_original_error_even_if_stderr_is_closed(self):
        error = RuntimeError('original')
        with mock.patch.dict(os.environ, {'AUTOWONDER_METRICS_FILE': str(self.root / 'missing' / 'metrics')}), mock.patch(
                'sys.stderr.write', side_effect=OSError('SECRET')):
            with self.assertRaises(RuntimeError) as caught:
                with self.metrics.measure('checkpoint'):
                    raise error
        self.assertIs(error, caught.exception)

    def test_unavailable_clock_cannot_prevent_successful_operation(self):
        warning = io.StringIO()
        with mock.patch.object(self.metrics.time, 'monotonic', side_effect=OSError('SECRET')), contextlib.redirect_stderr(warning):
            with self.metrics.measure('checkpoint'):
                result = 'mutation succeeded'
        self.assertEqual('mutation succeeded', result)
        self.assertIn('metrics', warning.getvalue())
        self.assertNotIn('SECRET', warning.getvalue())
        self.assertFalse(self.target.exists())

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_workflow_phase_is_correlated_only_when_allowlisted(self):
        with mock.patch.dict(os.environ, {'AUTOWONDER_METRICS_PHASE': 'backup'}):
            with self.metrics.measure('checkpoint'):
                pass
        with mock.patch.dict(os.environ, {'AUTOWONDER_METRICS_PHASE': 'SECRET-ARGV'}):
            with self.metrics.measure('checkpoint'):
                pass
        self.assertEqual('backup', self.records()[0].get('phase'))
        self.assertNotIn('phase', self.records()[1])
        self.assertNotIn('SECRET', self.target.read_text())

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_invalid_stage_and_counts_cannot_leak_unbounded_values(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.metrics.measure('SECRET-COMMAND'):
                pass
        self.assertFalse(self.target.exists())
        with self.metrics.measure('checkpoint') as counts:
            counts.update(files='secret', bytes=-1, calls=True)
        self.assertEqual({}, self.records()[0]['counts'])

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_save_restore_emit_timings_without_changing_cloud_state_guards(self):
        from test_operations_restore import OperationsRestoreTests
        fixture = OperationsRestoreTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        root = self.root / 'source'
        manifest = fixture.fixture(root)
        binding = fixture.manager.save(manifest, root, initialize=True)
        self.assertEqual(['bundle_collect', 'checkpoint'], [r['stage'] for r in self.records()])
        self.assertGreater(self.records()[0]['counts']['files'], 0)
        self.assertGreater(self.records()[0]['counts']['bytes'], 0)
        recovered = fixture.manager.restore(binding, self.root / 'fresh')
        self.assertEqual('resolved', recovered['status'])
        self.assertEqual('restore', self.records()[-1]['stage'])
        data = json.loads(manifest.read_text())
        data['operationsStore']['revision'] = '0' * 64
        manifest.write_text(json.dumps(data))
        before = dict(fixture.cloud.objects)
        with self.assertRaises(fixture.module.StateError):
            fixture.manager.save(manifest, root)
        self.assertEqual(before, fixture.cloud.objects)
        self.assertEqual('failure', self.records()[-1]['status'])

    @unittest.skipIf(os.name == 'nt', 'POSIX private metrics persistence only')
    def test_oss_transport_retains_absence_conflict_and_sanitized_failure(self):
        import operations_oss
        store = operations_oss.OssStore('cn-hangzhou')
        store._cli = ['fixture-ossutil']
        store.account_id = '1234567890123456'
        with mock.patch.object(store, '_credentials', return_value=b'fixture-secret'), mock.patch.object(
                operations_oss, '_run') as run:
            run.return_value = subprocess.CompletedProcess([], 0, b'{"ok":true}', b'')
            self.assertEqual(b'{"ok":true}', store._execute(['api', 'fixture-secret']))
            run.return_value = subprocess.CompletedProcess([], 1, b'', b'Error Code: NoSuchKey')
            self.assertIsNone(store._execute(['api', 'fixture-secret'], missing=True))
            run.return_value = subprocess.CompletedProcess([], 1, b'', b'Error Code: PreconditionFailed')
            with self.assertRaises(operations_oss.ConflictError):
                store._execute(['api', 'fixture-secret'], conflict=True)
            run.return_value = subprocess.CompletedProcess([], 1, b'', b'private remote output')
            with self.assertRaises(operations_oss.StoreError):
                store._execute(['api', 'fixture-secret'])
        self.assertEqual(['success', 'success', 'failure', 'failure'], [r['status'] for r in self.records()])
        self.assertEqual({'oss_transport'}, {r['stage'] for r in self.records()})
        self.assertNotIn('secret', self.target.read_text())
        self.assertNotIn('private', self.target.read_text())


if __name__ == '__main__':
    unittest.main()
