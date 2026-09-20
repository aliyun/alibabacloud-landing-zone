import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('ignored_environment_policy', SCRIPTS / 'upgrade_plan.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class IgnoredEnvironmentTests(unittest.TestCase):
    def test_token_is_excluded_from_current_and_historical_contracts(self):
        key = 'ANTHROPIC_AUTH_TOKEN'
        files = {
            'src/main/resources/application-local.yml': b'token: ${ANTHROPIC_AUTH_TOKEN:}\nrequired: ${DATABASE_PASSWORD}\n',
            'docs/community/application.env.example': b'ANTHROPIC_AUTH_TOKEN=\n',
        }
        historical = {key: [{'source': 'docs/community/application.env.example', 'tokenSha256': 'historical'}]}
        self.assertNotIn(key, policy.environment(files))
        for target in (files, {}):
            for values in ({}, {key: ''}, {key: 'synthetic-value'}):
                result = policy.analyze(historical, {}, target, values)
                for field in ('added', 'removed', 'changed', 'required'):
                    self.assertNotIn(key, result['environment'][field])
                self.assertFalse(any(key in reason for reason in result['blockedReasons']))
                if target:
                    self.assertIn('required environment value missing: DATABASE_PASSWORD', result['blockedReasons'])

    def test_existing_value_is_preserved_without_becoming_a_requirement(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / 'active.env'
            original.write_text('ANTHROPIC_AUTH_TOKEN=synthetic-value\nAUTOWONDER_SECRET_MASTER_KEY=synthetic-master\n')
            original.chmod(0o600)
            before = original.read_bytes()
            candidate = policy.prepare_candidate(original, None)
            values, _ = policy.candidate_env(candidate, '1.2.3')
            self.assertEqual('synthetic-value', values['ANTHROPIC_AUTH_TOKEN'])
            self.assertEqual(before, original.read_bytes())


if __name__ == '__main__':
    unittest.main()
