import importlib.util
from pathlib import Path
import unittest
import tempfile
import json
from unittest import mock

HELPER = Path(__file__).resolve().parents[1] / 'scripts/upgrade_plan.py'
spec = importlib.util.spec_from_file_location('release_baseline', HELPER)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class RepositoryTrustTest(unittest.TestCase):
    def test_empty_url_accepts_only_known_official_repositories(self):
        self.assertEqual({'https://github.com/aliyun/alibabacloud-landing-zone'},
                         policy.TRUSTED_REPOSITORIES)
        for origin in ('git@github.com:aliyun/alibabacloud-landing-zone.git',
                       'https://github.com/aliyun/alibabacloud-landing-zone/'):
            with self.subTest(origin=origin):
                policy.check_repository(origin, '')
        for origin in ('https://github.com/someone/auto-wonder', '/tmp/untrusted.git',
                       'https://github.com/aliyun/alibabacloud-landing-zone.evil'):
            with self.subTest(origin=origin):
                with self.assertRaises(policy.PlanError):
                    policy.check_repository(origin, '')

    def test_candidate_environment_rejects_executable_shell_syntax(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'candidate.env'
            path.write_text('EXAMPLE=$(id)\n')
            with self.assertRaisesRegex(policy.PlanError, 'executable shell syntax'):
                policy.candidate_env(path, '1.2.3')

    def test_atomic_writers_protect_empty_temp_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'protected'
            original = 'OLD=value\n'
            path.write_text(original)
            calls = []
            def reject_protection(source, target):
                calls.append((source, target))
                self.assertEqual(b'', Path(target).read_bytes())
                raise OSError('ACL protection failed')
            for operation in (lambda: policy.candidate_env(path, '1.2.3'), lambda: policy.save(path, {'private': 'value'})):
                with mock.patch.object(policy, 'protect_temp_acl', reject_protection, create=True):
                    with self.assertRaisesRegex(OSError, 'ACL protection failed'):
                        operation()
                self.assertEqual(original, path.read_text())
                self.assertEqual([path], list(Path(directory).iterdir()))
            self.assertEqual(2, len(calls))

    def test_source_contract_does_not_persist_secret_defaults(self):
        path = 'src/main/resources/application.yml'
        old = {path: b'password: ${SERVICE_PASSWORD:canary-old-secret}\n'}
        new = {path: b'password: ${SERVICE_PASSWORD:canary-new-secret}\n'}
        contract = policy.environment(old)
        self.assertNotIn('canary-old-secret', json.dumps(contract))
        findings = policy.analyze(contract, {}, new, {})
        self.assertEqual(['SERVICE_PASSWORD'], findings['environment']['changed'])
        self.assertNotIn('canary-new-secret', json.dumps(findings))

    def test_database_and_package_destination_changes_invalidate_fingerprint(self):
        data = {'upgrade': {'resourceIdentity': {}}, 'resources': {}}
        original = policy.fingerprint(data)
        variants = ({'rds_instance_id': 'changed'}, {'rds': {'instance_id': 'changed'}},
                    {'package_bucket': 'changed'}, {'packageBucket': 'changed'},
                    *({'oss': {key: 'changed'}} for key in ('control_endpoint', 'public_endpoint', 'runtime_endpoint', 'vpc_endpoint')))
        for resources in variants:
            with self.subTest(resources=resources):
                data['resources'] = resources
                self.assertNotEqual(original, policy.fingerprint(data))

    def test_environment_hash_alias_is_covered_by_fingerprint(self):
        data = {'upgrade': {'environmentPlanSha256': '1' * 64, 'environmentSha256': '1' * 64}}
        original = policy.fingerprint(data)
        data['upgrade']['environmentSha256'] = '2' * 64
        self.assertNotEqual(original, policy.fingerprint(data))


if __name__ == '__main__':
    unittest.main()
