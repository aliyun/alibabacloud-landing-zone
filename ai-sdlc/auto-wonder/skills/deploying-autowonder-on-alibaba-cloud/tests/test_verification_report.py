import importlib.util
import contextlib
import io
import json
import os
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('verify_cloud_skills',
    Path(__file__).resolve().parents[3] / 'scripts' / 'verify-cloud-skills.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class VerificationReportTests(unittest.TestCase):
    def test_runner_isolates_credentials_without_overriding_fixture_profiles(self):
        passed = {'tests': 1, 'failures': [], 'errors': [], 'skipped': []}
        response = SimpleNamespace(returncode=0, stdout=json.dumps(passed).encode())
        with patch.dict(os.environ, {'ALIBABA_CLOUD_CLI_CONFIG_FILE': '/user/private/profile',
                                     'ALICLOUD_SECRET_KEY': 'FAKE_SECRET'}), \
                patch.object(verify.subprocess, 'run', return_value=response) as run, \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(verify.main([]), 0)
        for call in run.call_args_list:
            environment = call.kwargs['env']
            self.assertNotIn('ALIBABA_CLOUD_CLI_CONFIG_FILE', environment)
            self.assertNotIn('ALICLOUD_SECRET_KEY', environment)
            self.assertNotEqual(environment['HOME'], os.environ.get('HOME'))
            self.assertFalse(Path(environment['HOME']).exists(), 'temporary home must be cleaned')

    def test_interop_worker_is_dispatched_and_its_result_controls_exit(self):
        good = {'tests': 2, 'failures': [], 'errors': [], 'skipped': [], 'seconds': 0.1}
        cases = [(good, 0), ({**good, 'failures': ['interop.failure']}, 1),
                 ({**good, 'errors': ['interop.import_error']}, 1),
                 ({**good, 'tests': 0}, 1)]
        for interop, expected in cases:
            with self.subTest(result=interop):
                def response(command, **kwargs):
                    value = interop if command[-1] == 'cloud-skill-interop' else good
                    return SimpleNamespace(returncode=0, stdout=json.dumps(value).encode())
                output = io.StringIO()
                with patch.object(verify.subprocess, 'run', side_effect=response) as run, \
                        contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(expected, verify.main([]))
                self.assertEqual([*verify.SKILLS, 'cloud-skill-interop'],
                                 [call.args[0][-1] for call in run.call_args_list])
                report = json.loads(output.getvalue())
                self.assertEqual('cloud-skill-interop', report['suites'][-1]['skill'])
                self.assertIn('seconds', report['suites'][-1])

    def test_interop_worker_failure_does_not_expose_output(self):
        good = {'tests': 1, 'failures': [], 'errors': [], 'skipped': []}
        def response(command, **kwargs):
            if command[-1] == 'cloud-skill-interop':
                return SimpleNamespace(returncode=1, stdout=b'FAKE_MASTER_SECRET')
            return SimpleNamespace(returncode=0, stdout=json.dumps(good).encode())
        output = io.StringIO()
        with patch.object(verify.subprocess, 'run', side_effect=response), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, verify.main([]))
        self.assertNotIn('FAKE_MASTER_SECRET', output.getvalue())

    def test_skips_are_incomplete_when_strict(self):
        result = {'tests': 12, 'failures': [], 'errors': [], 'skipped': ['native Windows unavailable']}
        self.assertEqual(verify.verdict([result], strict=False), 'passed-with-skips')
        self.assertEqual(verify.verdict([result], strict=True), 'incomplete')

    def test_failure_or_empty_suite_cannot_pass(self):
        good = {'tests': 1, 'failures': [], 'errors': [], 'skipped': []}
        self.assertEqual(verify.verdict([good], strict=True), 'passed')
        self.assertEqual(verify.verdict([{**good, 'errors': ['test_x']}], strict=False), 'failed')
        self.assertEqual(verify.verdict([{**good, 'tests': 0}], strict=False), 'failed')
        self.assertEqual(verify.verdict([], strict=False), 'failed')

    def run_fixture(self, contents):
        # Isolate unittest discovery/import state from the enclosing real suite.
        with tempfile.TemporaryDirectory() as directory, patch.dict(sys.modules), \
                patch.object(unittest, 'defaultTestLoader', unittest.TestLoader()):
            Path(directory, 'test_report_fixture.py').write_text(contents, encoding='utf-8')
            return verify.worker(Path(directory))

    def test_subtest_failure_error_and_skip_hide_parameter_values(self):
        result = self.run_fixture("""import unittest
class Fixture(unittest.TestCase):
    def test_failure(self):
        with self.subTest(credential='FAKE_REPORT_SECRET'):
            with self.subTest(nested='FAKE_NESTED_SECRET'):
                self.fail('FAKE_ASSERTION_SECRET')
    def test_error(self):
        with self.subTest(credential='FAKE_REPORT_SECRET'):
            raise ValueError('FAKE_EXCEPTION_SECRET')
    def test_skip(self):
        with self.subTest(credential='FAKE_REPORT_SECRET'):
            self.skipTest('FAKE_SKIP_SECRET')
""")
        self.assertEqual(result['tests'], 3)
        for key, name in [('failures', 'test_failure'), ('errors', 'test_error'), ('skipped', 'test_skip')]:
            with self.subTest(outcome=key):
                self.assertEqual(result[key], ['test_report_fixture.Fixture.' + name])
        self.assertNotIn('SECRET', json.dumps(result))
        self.assertEqual(verify.verdict([result], strict=True), 'failed')

    def test_unexpected_success_is_reported_as_failure(self):
        result = self.run_fixture("""import unittest
class Fixture(unittest.TestCase):
    @unittest.expectedFailure
    def test_known_failure(self):
        pass
""")
        self.assertEqual(result['tests'], 1)
        self.assertEqual(result['errors'], ['test_report_fixture.Fixture.test_known_failure'])
        self.assertEqual(verify.verdict([result], strict=False), 'failed')
        self.assertEqual(verify.verdict([result], strict=True), 'failed')
