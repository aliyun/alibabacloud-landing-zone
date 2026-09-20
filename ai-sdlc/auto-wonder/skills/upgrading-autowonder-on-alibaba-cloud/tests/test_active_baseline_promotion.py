import json
import subprocess
import unittest
import test_repository_promotion as repository

class ActiveBaselinePromotionTests(unittest.TestCase):
    def test_final_success_promotes_target_and_preserves_rollback_baseline(self):
        expressions = repository.RepositoryPromotionTests().mutations('rolling-upgrade')
        old = {'releaseId': 'a'*40, 'releaseVersion': '0.8.0', 'source': {'releaseId': 'a'*40},
               'artifacts': {'jar': {'sha256': '1'*64}}}
        data = {'repositoryCommit': 'b'*40, 'releaseVersion': '0.9.0',
                'source': {'releaseId': 'b'*40, 'baseline': {'releaseId': 'b'*40, 'releaseVersion': '0.9.0'}},
                'artifacts': {'jar': {'sha256': '2'*64}},
                'deployment': {'activeReleaseBaseline': old},
                'localContext': {'activeEnvFile': '/old.env', 'candidateEnvFile': '/candidate.env'},
                'upgrade': {'fromCommit': 'a'*40, 'toCommit': 'b'*40, 'planFingerprint': '3'*64}}
        def apply(expression, value):
            result = subprocess.run(['jq', '--argjson', 'nodes', '[]', '--argjson', 'ids', '[]',
                                     '--arg', 'status', 'failed', '--arg', 'failedInstanceId', 'i-b', expression],
                                    input=json.dumps(value), text=True, capture_output=True, check=True)
            return json.loads(result.stdout)
        for expression in expressions[:-1]:
            self.assertEqual(old, apply(expression, data)['deployment']['activeReleaseBaseline'])
        # A failed/partial rollout has never populated success-only history.
        data['localContext']['protectedEnvFile'] = '/candidate.env'
        rollback = repository.RepositoryPromotionTests().mutations('rollback-upgrade')[-1]
        preacceptance = apply(rollback, data)
        self.assertEqual('0.8.0', preacceptance['releaseVersion'])
        self.assertEqual(old['source'], preacceptance['source'])
        self.assertEqual(old['artifacts'], preacceptance['artifacts'])
        self.assertEqual('/old.env', preacceptance['localContext']['protectedEnvFile'])
        updated = apply(expressions[-1], data)
        expected = {'releaseId': 'b'*40, 'releaseVersion': '0.9.0', 'source': data['source'], 'artifacts': data['artifacts']}
        self.assertEqual(expected, updated['deployment'].get('activeReleaseBaseline'))
        self.assertEqual(old, updated['upgrade'].get('previousReleaseBaseline'))
        self.assertEqual('/candidate.env', updated['localContext']['activeEnvFile'])
        retried = apply(expressions[-1], updated)
        self.assertEqual(old, retried['upgrade']['previousReleaseBaseline'])
        rollback = repository.RepositoryPromotionTests().mutations('rollback-upgrade')[-1]
        restored = apply(rollback, updated)
        self.assertEqual(old, restored['deployment']['activeReleaseBaseline'])
        self.assertEqual('0.8.0', restored['releaseVersion'])

if __name__ == '__main__':
    unittest.main()
