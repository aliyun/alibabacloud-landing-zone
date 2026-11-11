"""Quality decisions against real Git trees and portable evidence directories."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-community-quality.py'


class QualityTests(unittest.TestCase):
    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], env=self.env,
                                       stderr=subprocess.PIPE, text=True).strip()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='quality portable ')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / 'different repo 名称'
        self.evidence = self.root / 'detached evidence'
        self.repo.mkdir()
        self.evidence.mkdir()
        self.env = {**os.environ, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                    'GIT_AUTHOR_NAME': 'Fixture', 'GIT_AUTHOR_EMAIL': 'test@example.test',
                    'GIT_COMMITTER_NAME': 'Fixture', 'GIT_COMMITTER_EMAIL': 'test@example.test'}
        self.git('init', '-q')
        self.git('config', 'core.hooksPath', os.devnull)
        self.git('checkout', '-qb', 'sync-fixture')
        config = self.repo / 'src/main/resources/application.yml'
        config.parent.mkdir(parents=True)
        config.write_text('autowonder:\n  runtime:\n    recommended-version: ${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:9.8.7}\n')
        (self.repo / 'product.txt').write_text('unchanged product')
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        self.commit = self.git('rev-parse', 'HEAD')
        self.tree = self.git('rev-parse', 'HEAD^{tree}')
        self.runtime = dict(verdict='PASS', cleanup=True, sourceRuntimeVersion='9.8.7',
                            runtimeVersion='9.8.7', runtimeRecommendationSource='application.yml',
                            artifactSha256='a' * 64, checks=['executor_online', 'routing_matches',
                            'server_acked', 'server_running', 'dispatch_succeeded', 'downloaded_artifact_matches'])
        self.manifest = dict(schemaVersion=1, qualityContractVersion=1, branch='sync-fixture',
                             cycle=1, executionFingerprintCounts={'verifyCheck': 1}, repairFingerprintCounts={},
                             finalSourceSha=self.commit, testedCommit=self.commit, acceptedTree=self.tree,
                             staticGates={'verdict': 'PASS'}, e2e={'verdict': 'PASS', 'cleanup': 'PASS'},
                             openFindings=0, openRequiredCheckpoints=0,
                             reviews={k: 'PASS' for k in ['masterSemantics', 'communityBoundary', 'releaseReadiness']},
                             implementer='implementation-task', qualityChecks=[], reviewEvidence={})
        for check_id in ['static', 'configuration', 'json-protocol', 'runtime-delivery', 'sls-disabled',
                         'sls-enabled', 'cleanup', 'encryption', 'migration', 'feature-switches']:
            self.manifest['qualityChecks'].append(dict(id=check_id, status='PASS', exitCode=0,
                level='runtime-e2e' if check_id == 'runtime-delivery' else 'integration',
                testedCommit=self.commit, evidence=[self.write_proof(check_id + '.txt', 'actual fixture evidence')]))
        for key in self.manifest['reviews']:
            self.manifest['reviewEvidence'][key] = dict(reviewer='independent-task',
                executionRef='review-task-42', independent=True, candidateTree=self.tree,
                evidence=[self.write_proof(key + '.md', 'independent review fixture')])

    def write_proof(self, name, content):
        path = self.evidence / name
        path.write_text(content)
        return dict(path=name, sha256=hashlib.sha256(path.read_bytes()).hexdigest())

    def check(self, check_id):
        return next(c for c in self.manifest['qualityChecks'] if c['id'] == check_id)

    def run_gate(self, phase='local'):
        self.manifest.setdefault('runtimeContext', self.write_proof('runtime-context.json', json.dumps({'git': {'commit': self.commit, 'dirty': False}})))
        self.manifest['runtimeEvidence'] = self.write_proof('runtime-dispatch.json', json.dumps(self.runtime))
        path = self.evidence / 'manifest.json'
        path.write_text(json.dumps(self.manifest))
        proc = subprocess.run([sys.executable, str(SCRIPT), '--repo', str(self.repo), '--manifest', str(path),
                               '--evidence-root', str(self.evidence), '--phase', phase],
                              cwd=self.root, env=self.env, text=True, capture_output=True)
        try:
            report = json.loads(proc.stdout)
        except ValueError:
            report = {'verdict': 'NO_REPORT', 'stderr': proc.stderr}
        return proc.returncode, report

    def assert_rejected(self, expected):
        code, report = self.run_gate()
        self.assertNotEqual(code, 0)
        self.assertIn(expected, json.dumps(report))

    def test_valid_evidence_runs_outside_repo_with_spaces_and_future_runtime(self):
        code, report = self.run_gate()
        self.assertEqual(code, 0, report)
        self.assertEqual(report['verdict'], 'PASS')

    def test_runtime_failure_cannot_be_hidden_by_summary_pass(self):
        self.runtime.update(verdict='FAIL', failureKind='QODER_TOKEN_REQUIRED')
        self.assert_rejected('runtime')

    def test_missing_required_check_is_rejected(self):
        self.manifest['qualityChecks'].remove(self.check('sls-enabled'))
        self.assert_rejected('sls-enabled')

    def test_incomplete_and_nonzero_checks_are_rejected(self):
        for status in ['FAIL', 'NOT_RUN', 'PARTIAL', 'NOT_APPLICABLE']:
            with self.subTest(status=status):
                self.check('runtime-delivery')['status'] = status
                self.assert_rejected('runtime-delivery')
        self.check('runtime-delivery')['status'] = 'PASS'
        self.check('runtime-delivery')['exitCode'] = 1
        self.assert_rejected('runtime-delivery')

    def test_only_conditional_checks_allow_explained_not_applicable(self):
        self.check('encryption').update(status='NOT_APPLICABLE', reason='No changed encryption consumers')
        code, report = self.run_gate()
        self.assertEqual(code, 0, report)
        self.check('encryption')['reason'] = ''
        self.assert_rejected('encryption')

    def test_mock_runtime_cannot_be_promoted_to_real_e2e(self):
        self.check('runtime-delivery')['level'] = 'unit'
        self.assert_rejected('runtime-delivery')

    def test_runtime_must_match_source_and_have_delivery_assertions(self):
        self.runtime['sourceRuntimeVersion'] = '0.2.163'
        self.assert_rejected('runtime')
        self.runtime['sourceRuntimeVersion'] = '9.8.7'
        self.runtime['checks'].remove('downloaded_artifact_matches')
        self.assert_rejected('runtime')

    def test_explicit_override_needs_protected_lifecycle_resolution_evidence(self):
        self.runtime['runtimeVersion'] = '9.9.0'
        self.assert_rejected('runtime')
        self.runtime['runtimeRecommendationSource'] = 'lifecycle-app.env'
        code, report = self.run_gate()
        self.assertEqual(code, 0, report)

    def test_self_review_and_missing_review_receipt_are_rejected(self):
        self.manifest['reviewEvidence']['masterSemantics']['reviewer'] = 'implementation-task'
        self.assert_rejected('review')
        self.manifest['reviewEvidence']['masterSemantics']['reviewer'] = 'independent-task'
        self.manifest['reviewEvidence']['masterSemantics']['executionRef'] = ''
        self.assert_rejected('review')

    def test_stale_tree_or_dirty_tracked_files_are_rejected(self):
        self.manifest['acceptedTree'] = 'b' * 40
        self.assert_rejected('tree')
        self.manifest['acceptedTree'] = self.tree
        (self.repo / 'product.txt').write_text('changed')
        self.assert_rejected('dirty')

    def test_tampered_missing_and_escaping_evidence_are_rejected(self):
        entry = self.check('configuration')['evidence'][0]
        (self.evidence / entry['path']).write_text('tampered')
        self.assert_rejected('evidence')
        entry['path'] = '../outside.txt'
        self.assert_rejected('evidence')
        entry['path'] = '/absolute.txt'
        self.assert_rejected('evidence')

    def test_required_checkpoint_and_boolean_exit_code_are_rejected(self):
        self.manifest['openRequiredCheckpoints'] = 1
        self.assert_rejected('checkpoint')
        self.manifest['openRequiredCheckpoints'] = 0
        self.check('static')['exitCode'] = False
        self.assert_rejected('static')

    def test_component_reuse_requires_equal_declared_git_inputs(self):
        original = self.commit
        self.git('commit', '--allow-empty', '-qm', 'metadata only')
        new = self.git('rev-parse', 'HEAD')
        self.manifest.update(finalSourceSha=new, testedCommit=new)
        code, report = self.run_gate()
        self.assertEqual(code, 0, report)
        (self.repo / 'docs.txt').write_text('new docs')
        self.git('add', '.')
        self.git('commit', '-qm', 'docs')
        self.commit = self.git('rev-parse', 'HEAD')
        self.manifest.pop('runtimeContext', None)
        self.manifest.update(finalSourceSha=self.git('rev-parse', 'HEAD'), testedCommit=self.git('rev-parse', 'HEAD'),
                             acceptedTree=self.git('rev-parse', 'HEAD^{tree}'))
        for review in self.manifest['reviewEvidence'].values(): review['candidateTree'] = self.manifest['acceptedTree']
        for check in self.manifest['qualityChecks']:
            check.update(testedCommit=self.manifest['finalSourceSha'])
        self.check('static').update(testedCommit=original, inputs=['product.txt'], reuseReason='unchanged component inputs')
        code, report = self.run_gate()
        self.assertEqual(code, 0, report)
        self.check('static')['inputs'] = ['docs.txt']
        self.assert_rejected('reuse')

    def test_submitted_phase_requires_tracking_to_match_candidate(self):
        code, _ = self.run_gate('submitted')
        self.assertNotEqual(code, 0)
        self.git('update-ref', 'refs/remotes/origin/sync-fixture', self.commit)
        self.manifest['remoteSourceSha'] = self.commit
        code, report = self.run_gate('submitted')
        self.assertEqual(code, 0, report)

    def test_native_runtime_context_cannot_belong_to_a_different_tree(self):
        (self.repo / 'product.txt').write_text('other tested content')
        self.git('add', '.')
        self.git('commit', '-qm', 'other source')
        other = self.git('rev-parse', 'HEAD')
        self.git('reset', '--hard', self.commit)
        self.manifest['runtimeContext'] = self.write_proof('wrong-context.json', json.dumps({'git': {'commit': other, 'dirty': False}}))
        self.assert_rejected('runtime')

    def test_conflicting_current_runtime_summary_is_rejected(self):
        self.manifest['e2e']['withRuntime'] = {'status': 'EXTERNAL_CONDITION_CHECKPOINT', 'result': 'exit 1'}
        self.assert_rejected('runtime')

    def test_symlink_cannot_escape_evidence_root(self):
        outside = self.root / 'external-proof'
        outside.write_text('outside')
        path = self.evidence / 'escape'
        path.symlink_to(outside)
        self.check('static')['evidence'] = [{'path': 'escape', 'sha256': hashlib.sha256(outside.read_bytes()).hexdigest()}]
        self.assert_rejected('evidence')

    def replace_config(self, content):
        (self.repo / 'src/main/resources/application.yml').write_text(content)
        self.git('add', '.')
        self.git('commit', '-qm', 'config change')
        self.commit = self.git('rev-parse', 'HEAD')
        self.tree = self.git('rev-parse', 'HEAD^{tree}')
        self.manifest.update(finalSourceSha=self.commit, testedCommit=self.commit, acceptedTree=self.tree)
        self.manifest.pop('runtimeContext', None)
        for check in self.manifest['qualityChecks']: check['testedCommit'] = self.commit
        for review in self.manifest['reviewEvidence'].values(): review['candidateTree'] = self.tree

    def test_git_environment_cannot_redirect_explicit_repository(self):
        self.env['GIT_DIR'] = str(self.repo / '.git')
        self.env['GIT_WORK_TREE'] = str(self.repo)
        self.repo = self.root / 'empty repository'
        self.repo.mkdir()
        self.assert_rejected('Git')

    def test_recommendation_matches_native_namespace_and_scalar_rules(self):
        self.replace_config('autowonder:\n  runtime:\n    recommended-version: "9.8.7" # valid literal\n')
        code, report = self.run_gate()
        self.assertEqual(code, 0, report)
        self.replace_config('unrelated:\n  recommended-version: ${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:9.8.7}\n')
        self.assert_rejected('runtime')

    def test_duplicate_runtime_namespace_fails_closed(self):
        self.replace_config('autowonder:\n  runtime:\n    recommended-version: 9.8.7\nautowonder:\n  runtime:\n    recommended-version: 9.8.7\n')
        self.assert_rejected('runtime')

    def test_cycle_and_fingerprint_accounting_remain_required(self):
        self.manifest['cycle'] = True
        self.assert_rejected('cycle')
        self.manifest['cycle'] = 1
        self.manifest['repairFingerprintCounts'] = {'repair': -1}
        self.assert_rejected('repairFingerprintCounts')
        self.manifest['repairFingerprintCounts'] = {}
        self.manifest.pop('executionFingerprintCounts')
        self.assert_rejected('executionFingerprintCounts')

    def test_promisor_clone_is_rejected_without_lazy_object_fetch(self):
        self.git('config', 'remote.unavailable.promisor', 'true')
        self.assert_rejected('promisor')

    def test_legacy_summary_only_evidence_fails_with_upgrade_instruction(self):
        del self.manifest['qualityContractVersion']
        self.assert_rejected('qualityContractVersion')


if __name__ == '__main__':
    unittest.main()
