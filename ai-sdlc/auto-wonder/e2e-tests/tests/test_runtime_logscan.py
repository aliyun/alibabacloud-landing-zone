"""Runtime diagnostics must be attributable to a successful owned scenario."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
CHECKS = ['executor_online', 'routing_matches', 'server_acked', 'server_running',
          'dispatch_succeeded', 'downloaded_artifact_matches']
EOF = '2026-10-10 07:35:27,110|WARN|||ExecutorWsEndpoint|thread|WS error executorId=45 java.io.EOFException'
USAGE = ('2026-10-10 07:35:24,722|WARN|usage-rid|POST /api/daemon/dispatches/10/artifacts|'
         'DispatchAiUsageService|thread|usage artifact ingest skipped artifactId=7 '
         'ossRef=bucket/t/13/workitem/6/dispatch/10/objects/sha256/' + 'a' * 64
         + '/observability/usage.json workitemId=6 dispatchId=10 reason=no_entries ')


def evidence():
    return dict(verdict='PASS', cleanup=True, executorId=45, workitemId=6, dispatchId=10,
                artifactId=8, workspaceId=13, checks=CHECKS,
                cleanupLogStart={'stdout': 0, 'file': 0},
                cleanupLogEnd={'stdout': 1, 'file': 1})


class RuntimeLogscanTests(unittest.TestCase):
    def scan(self, line, proof):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / 'app.log').write_text(line + '\n')
            (state / 'file.log').write_text(line + '\n')
            if proof is not None:
                (state / 'runtime-dispatch.json').write_text(json.dumps(proof))
            result = subprocess.run([str(ROOT / 'e2e-tests/logscan.sh')],
                env=dict(os.environ, AW_E2E_STATE_DIR=str(state),
                         AW_E2E_APP_LOG=str(state / 'app.log'),
                         AW_E2E_FILE_LOG=str(state / 'file.log')),
                capture_output=True, text=True, timeout=10)
            return result.returncode

    def test_exact_owned_success_diagnostics_are_attributed(self):
        for line in (EOF, USAGE):
            with self.subTest(line=line):
                self.assertEqual(self.scan(line, evidence()), 0)

    def test_untrusted_or_incomplete_runtime_evidence_does_not_excuse_diagnostics(self):
        proofs = [None, {}, {**evidence(), 'verdict': 'FAIL'},
                  {**evidence(), 'cleanup': False}, {**evidence(), 'cleanup': 1},
                  {**evidence(), 'checks': CHECKS[:-1]}, {**evidence(), 'dispatchId': True}]
        for proof in proofs:
            for line in (EOF, USAGE):
                with self.subTest(proof=proof, line=line):
                    self.assertNotEqual(self.scan(line, proof), 0)

    def test_other_diagnostics_and_ids_are_rejected(self):
        lines = [EOF.replace('45', '46'), EOF.replace('WARN', 'ERROR'),
                 EOF.replace('EOFException', 'IOException'),
                 EOF.replace('ExecutorWsEndpoint', 'OtherLogger'),
                 USAGE.replace('reason=no_entries', 'reason=empty_content'),
                 USAGE.replace('workitemId=6', 'workitemId=66'),
                 USAGE.replace('dispatchId=10', 'dispatchId=100'),
                 USAGE.replace('/dispatch/10/', '/dispatch/100/'),
                 USAGE.replace('/t/13/', '/t/130/'),
                 USAGE.replace('/dispatches/10/', '/dispatches/100/'),
                 USAGE.replace('WARN', 'ERROR'),
                 USAGE.replace('DispatchAiUsageService', 'OtherLogger')]
        for line in lines:
            with self.subTest(line=line):
                self.assertNotEqual(self.scan(line, evidence()), 0)

    def test_eof_requires_per_file_cleanup_window(self):
        for changes in ({'cleanupLogStart': {'stdout': 1, 'file': 1}},
                        {'cleanupLogEnd': {'stdout': 0, 'file': 0}},
                        {'cleanupLogStart': {}}, {'cleanupLogEnd': {}},
                        {'cleanupLogStart': {'stdout': 0, 'file': 1}}):
            with self.subTest(changes=changes):
                self.assertNotEqual(self.scan(EOF, {**evidence(), **changes}), 0)


if __name__ == '__main__':
    unittest.main()
