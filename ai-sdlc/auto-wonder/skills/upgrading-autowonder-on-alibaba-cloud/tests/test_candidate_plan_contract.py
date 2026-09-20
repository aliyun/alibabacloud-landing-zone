import importlib.util
from pathlib import Path
import tempfile
import os
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
spec = importlib.util.spec_from_file_location('candidate_contract', SCRIPTS / 'upgrade_plan.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)

class CandidateContractTests(unittest.TestCase):
    def test_spring_defaults_nested_and_unknown_empty(self):
        files = {'src/main/resources/application.yml': b'x: ${FEATURE:true}\ny: ${OSS_BACKUP_BUCKET:}\nz: ${OUTER:${INNER:default}}\nu: ${UNKNOWN:}\nr: ${REQUIRED}\n'}
        result = policy.analyze({}, {}, files, {})
        self.assertEqual(['REQUIRED', 'UNKNOWN'], result['environment']['required'])
        self.assertIn('INNER', result['environment']['added'])

    def test_defaults_override_examples_but_required_guards_remain(self):
        files = {'src/main/resources/application.yml':
                 b'x: ${FEATURE:true}\ny: ${OSS_BACKUP_BUCKET:}\nz: ${S3_ENDPOINT:}\n',
                 'docs/community/application.env.example': b'FEATURE=\nOSS_BACKUP_BUCKET=\nS3_ENDPOINT=\n'}
        disabled = policy.analyze({}, {}, files, {'S3_ENABLED': 'false'})
        self.assertEqual([], disabled['environment']['required'])
        enabled = policy.analyze({}, {}, files, {'S3_ENABLED': 'true'})
        self.assertEqual(['S3_ENDPOINT'], enabled['environment']['required'])
        self.assertEqual(['required environment value missing: S3_ENDPOINT'], enabled['blockedReasons'])

    def test_control_host_variables_do_not_become_application_requirements(self):
        files = {'skills/upgrading-autowonder-on-alibaba-cloud/scripts/lib.sh':
                 b'config=${CLI_CONFIG:-${HOME}/.aliyun/config.json}\nprintf "${TOOL_HOST_VALUE}"\n',
                 'src/main/resources/application.yml': b'app: ${APPLICATION_REQUIRED}\n'}
        result = policy.analyze({}, {}, files, {})
        self.assertIn('HOME', result['environment']['added'])
        self.assertEqual(['APPLICATION_REQUIRED'], result['environment']['required'])

    def test_candidate_is_independent_and_versions_match(self):
        with tempfile.TemporaryDirectory() as temp:
            original = Path(temp) / 'original.env'
            original.write_bytes(b'# original\r\nAUTOWONDER_VERSION=0.1.0\r\nAUTOWONDER_SECRET_MASTER_KEY=synthetic-master\r\n')
            original.chmod(0o600)
            before = original.read_bytes()
            candidate = policy.prepare_candidate(original, None)
            values, checksum = policy.candidate_env(candidate, '2.0.0', '1.2.3')
            self.assertNotEqual(original, candidate)
            self.assertEqual(before, original.read_bytes())
            self.assertEqual('1.2.3', values['AUTOWONDER_VERSION'])
            self.assertEqual('2.0.0', values['AUTOWONDER_RUNTIME_RECOMMENDED_VERSION'])
            self.assertEqual(0, candidate.stat().st_mode & 0o077)
            self.assertEqual(checksum, policy.digest(candidate.read_bytes()))

    def test_candidate_rejects_original_and_alias(self):
        with tempfile.TemporaryDirectory() as temp:
            original = Path(temp) / 'original.env'
            original.write_text('SECRET=unchanged\n')
            original.chmod(0o600)
            alias = Path(temp) / 'alias.env'
            alias.symlink_to(original)
            hardlink = Path(temp) / 'hardlink.env'
            os.link(original, hardlink)
            for target in (original, alias, hardlink):
                with self.assertRaises(policy.PlanError):
                    policy.prepare_candidate(original, target)
            self.assertEqual('SECRET=unchanged\n', original.read_text())

if __name__ == '__main__':
    unittest.main()
