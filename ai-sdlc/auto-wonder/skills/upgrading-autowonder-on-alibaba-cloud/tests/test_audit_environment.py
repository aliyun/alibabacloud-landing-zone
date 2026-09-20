import importlib.util
from pathlib import Path
import tempfile
import json
import subprocess
import re
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
spec = importlib.util.spec_from_file_location('audit_environment_plan', SCRIPTS / 'upgrade_plan.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
APP = 'src/main/resources/application.yml'

class AuditEnvironmentTests(unittest.TestCase):
    def test_explicit_candidate_cannot_replace_or_remove_master_key(self):
        with tempfile.TemporaryDirectory() as temp:
            original, candidate = Path(temp) / 'original.env', Path(temp) / 'candidate.env'
            original.write_text('AUTOWONDER_SECRET_MASTER_KEY="original-canary"\n')
            original.chmod(0o600)
            for value in ('AUTOWONDER_SECRET_MASTER_KEY=replacement-canary\n', 'OTHER=value\n'):
                candidate.write_text(value)
                candidate.chmod(0o600)
                with self.assertRaises(policy.PlanError) as error:
                    policy.prepare_candidate(original, candidate)
                self.assertNotIn('canary', str(error.exception))
                self.assertEqual(value, candidate.read_text())
            candidate.write_text("AUTOWONDER_SECRET_MASTER_KEY='original-canary'\n")
            self.assertEqual(candidate, policy.prepare_candidate(original, candidate))

    def test_candidate_cli_rejects_changed_key_without_printing_values(self):
        with tempfile.TemporaryDirectory() as temp:
            original, candidate = Path(temp) / 'original.env', Path(temp) / 'candidate.env'
            original.write_text('AUTOWONDER_SECRET_MASTER_KEY=original-canary\n')
            candidate.write_text('AUTOWONDER_SECRET_MASTER_KEY=changed-canary\n')
            result = subprocess.run(['python3', str(SCRIPTS / 'upgrade_plan.py'), 'check-candidate',
                                     '--original-env-file', str(original), '--env-file', str(candidate)],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn('canary', result.stdout + result.stderr)
            candidate.write_text('AUTOWONDER_SECRET_MASTER_KEY="original-canary"\n')
            result = subprocess.run(['python3', str(SCRIPTS / 'upgrade_plan.py'), 'check-candidate',
                                     '--original-env-file', str(original), '--env-file', str(candidate)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('', result.stdout)

    def test_existing_required_and_lost_default_are_checked(self):
        old = policy.environment({APP: b'x: ${EXTERNAL_URL:http://old}\ny: ${ALWAYS_REQUIRED}\n'})
        result = policy.analyze(old, {}, {APP: b'x: ${EXTERNAL_URL}\ny: ${ALWAYS_REQUIRED}\n'}, {})
        self.assertEqual(['ALWAYS_REQUIRED', 'EXTERNAL_URL'], result['environment']['required'])
        self.assertEqual(['required environment value missing: ALWAYS_REQUIRED',
                          'required environment value missing: EXTERNAL_URL'], result['blockedReasons'])

    def test_nested_fallback_is_required_only_when_outer_is_absent(self):
        files = {APP: b'x: ${PRIMARY:${SECONDARY:${LAST}}}\n'}
        for values, required in (({}, ['LAST']), ({'PRIMARY': 'set'}, []),
                                 ({'SECONDARY': 'set'}, []), ({'LAST': 'set'}, ['LAST'])):
            with self.subTest(values=values):
                result = policy.analyze({}, {}, files, values)
                self.assertEqual(required, result['environment']['required'])
                self.assertEqual([] if values else ['required environment value missing: LAST'], result['blockedReasons'])

    def test_runtime_gate_accepts_complete_planner_contract_and_rejects_malformed_keys(self):
        old = policy.environment({APP: b'x: ${EXTERNAL_URL:http://old}\n'})
        findings = policy.analyze(old, {}, {APP: b'x: ${EXTERNAL_URL}\n'}, {'EXTERNAL_URL': 'https://configured'})
        self.assertEqual([], findings['environment']['added'])
        self.assertEqual(['EXTERNAL_URL'], findings['environment']['required'])
        for skill in ('upgrading', 'deploying'):
            script = SCRIPTS.parents[1] / (skill + '-autowonder-on-alibaba-cloud/scripts/internal/operations.sh')
            text = script.read_text()
            gate = re.search(r"jq -e '(\s*\.upgrade.environment as \$environment.*?)' \"\$manifest\"", text, re.S).group(1)
            for contract, success in ((findings['environment'], True),
                                      ({'added': [], 'required': ['INVALID=key']}, False),
                                      ({'added': [], 'required': ['KEY', 'KEY']}, False)):
                with self.subTest(skill=skill, contract=contract):
                    result = subprocess.run(['jq', '-e', gate], input=json.dumps({'upgrade': {'environment': contract}}),
                                            text=True, capture_output=True)
                    self.assertEqual(success, result.returncode == 0, result.stderr)

    def test_runtime_managed_name_without_default_still_requires_value(self):
        result = policy.analyze({}, {}, {APP: b'x: ${S3_REGION}\n'}, {})
        self.assertEqual(['required environment value missing: S3_REGION'], result['blockedReasons'])

    def test_feature_guard_uses_target_default_and_skips_disabled_consumers(self):
        files = {APP: b's3: ${S3_ENABLED:true}\nendpoint: ${S3_ENDPOINT:}\nsls: ${AUTOWONDER_SLS_ENABLED:false}\nproject: ${SLS_PROJECT:}\ntopic: ${SLS_TOPIC:}\nsource: ${SLS_SOURCE:}\n'}
        result = policy.analyze({}, {}, files, {})
        self.assertEqual(['S3_ENDPOINT'], result['environment']['required'])
        result = policy.analyze({}, {}, files, {'S3_ENABLED': 'false', 'AUTOWONDER_SLS_ENABLED': 'true'})
        self.assertEqual(['SLS_PROJECT'], result['environment']['required'])

    def test_stale_active_baseline_cannot_override_current_version(self):
        data = {'releaseVersion': '0.9.0', 'repositoryCommit': 'b' * 40,
                'deployment': {'activeCommit': 'b' * 40, 'activeReleaseBaseline':
                               {'releaseId': 'a' * 40, 'releaseVersion': '0.8.0'}}}
        # Exercise the workspace planning guard before artifact IO.
        from unittest.mock import patch
        import argparse
        import time
        data.update(upgrade={'targetVerification': {'fingerprint': 'verified', 'nodes': []}},
                    upgradeInventory={'status': 'verified', 'activeCommit': 'b' * 40,
                    'targetVerificationFingerprint': 'verified', 'nodes': [], 'verifiedEpoch': time.time()})
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'VERSION').write_text('0.9.0')
            args = argparse.Namespace(source_dir=temp, current_commit=None, workspace_current_content=True,
                                      force_redeploy=True, manifest=str(root/'manifest.json'), baseline_dir=None)
            with patch.object(policy, 'artifact_evidence', side_effect=RuntimeError('artifact boundary')):
                with self.assertRaisesRegex(RuntimeError, 'artifact boundary'):
                    try:
                        policy.plan(args, data)
                    except policy.PlanError as error:
                        self.fail(str(error))

if __name__ == '__main__':
    unittest.main()
