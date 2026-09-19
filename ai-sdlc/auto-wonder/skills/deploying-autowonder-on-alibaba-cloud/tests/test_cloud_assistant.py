import base64
import json
import itertools
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
CORE = SCRIPTS / 'cloud_assistant.py'


class CloudAssistantCoreTests(unittest.TestCase):
    def core(self, action, document):
        self.assertTrue(CORE.is_file(), 'Shared Cloud Assistant core is required')
        return subprocess.run([sys.executable, '-B', str(CORE), action],
                              input=json.dumps(document), text=True, capture_output=True)

    def test_current_and_legacy_response_shapes(self):
        fixtures = [
            ({'InvokeId': 't-current', 'InvocationResults': {'InvocationResult': [
                {'InvokeRecordStatus': 'Finished', 'ExitCode': 0}]}}, 't-current', 'Finished'),
            ({'InvocationId': 't-old', 'Invocation': {'InvocationResults': {'InvocationResult': [
                {'InvocationStatus': 'Success', 'ExitCode': '0'}]}}}, 't-old', 'Success'),
        ]
        for response, invocation, status in fixtures:
            with self.subTest(invocation=invocation):
                self.assertEqual(invocation, self.core('invocation-id', response).stdout.strip())
                self.assertEqual(status, self.core('status', response).stdout.strip())
                self.assertEqual('0', self.core('exit-code', response).stdout.strip())
                parsed = json.loads(self.core('poll', response).stdout)
                self.assertEqual('success', parsed['state'])

    def test_both_history_layouts_and_incomplete_results_block_retry(self):
        for key in ('remoteInvocations', 'upgrade'):
            for status in ('submitted', 'poll-timeout', 'error', 'failed', None):
                document = {'operationsStore': {'revision': 'r1'}}
                entries = [{'invokeId': 'old-call', 'status': status}]
                document[key] = entries if key == 'remoteInvocations' else {'remoteInvocations': entries}
                with self.subTest(key=key, status=status):
                    result = self.core('assert-settled', document)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('unresolved', result.stderr)
        self.assertEqual(0, self.core('assert-settled', {'operationsStore': {},
            'remoteInvocations': [{'status': 'finished'}],
            'upgrade': {'remoteInvocations': [{'status': 'finished'}]}}).returncode)

    def test_migration_and_bound_unknown_states_remain_blocked(self):
        for pending in ({'remoteSubmission': {'status': 'unknown'}},
                        {'terraform': {'pendingOperation': 'apply'}},
                        {'phase': 'terraform-destroy', 'status': 'unknown'}):
            self.assertNotEqual(0, self.core('assert-settled', dict(pending, operationsStore={})).returncode)
        self.assertNotEqual(0, self.core('assert-settled', {'operationsMigration': {'status': 'unknown'}}).returncode)
        self.assertEqual(0, self.core('assert-settled', {'remoteSubmission': {'status': 'unknown'}}).returncode)

    def test_failure_or_missing_exit_code_never_releases_remote_output(self):
        for status, code in (('Finished', 9), ('Finished', None), ('Failed', 0), ('TimedOut', 0)):
            response = {'Status': status, 'ExitCode': code, 'Output': 'SECRET_REMOTE_CANARY'}
            result = self.core('poll', response)
            self.assertEqual('failure', json.loads(result.stdout)['state'])
            self.assertNotIn('SECRET_REMOTE_CANARY', result.stdout + result.stderr)
        result = self.core('poll', {'Status': 'Finished', 'ExitCode': 0, 'Output': 'bad-SECRET_REMOTE_CANARY'})
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('SECRET_REMOTE_CANARY', result.stdout + result.stderr)

    def test_stdin_utf8_does_not_depend_on_host_locale(self):
        response = json.dumps({'InvokeId': 't-utf8', 'description': '中文'}, ensure_ascii=False)
        result = subprocess.run([sys.executable, '-B', str(CORE), 'invocation-id'],
                                input=('\ufeff' + response).encode('utf-8'), capture_output=True,
                                env=dict(os.environ, PYTHONIOENCODING='ascii'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b't-utf8', result.stdout.strip())

    def test_success_output_is_utf8_and_pending_has_no_output(self):
        response = {'Status': 'Finished', 'ExitCode': 0,
                    'Output': base64.b64encode('  已完成\n'.encode()).decode()}
        self.assertEqual('已完成', json.loads(self.core('poll', response).stdout)['output'])
        self.assertEqual({'status': 'Pending', 'exitCode': -1, 'state': 'pending'},
                         json.loads(self.core('poll', {}).stdout))


@unittest.skipUnless(shutil.which('bash') and shutil.which('jq'), 'bash and jq required')
class CloudAssistantShellTests(unittest.TestCase):
    def test_shell_parser_uses_shared_result_contract(self):
        response = {'InvocationResults': {'InvocationResult': [{'Status': 'Finished', 'ExitCode': 0}]}}
        result = subprocess.run(['bash', '-c', 'source "$1"; cloud_assistant_status', '_', str(SCRIPTS / 'lib.sh')],
                                input=json.dumps(response), text=True, capture_output=True,
                                env=dict(os.environ, AUTOWONDER_PYTHON=sys.executable))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('Finished', result.stdout.strip())

    def test_submission_evidence_and_invalid_results_cannot_replay(self):
        paths = [SCRIPTS / 'internal/operations.sh',
                 SCRIPTS.parents[1] / 'upgrading-autowonder-on-alibaba-cloud/scripts/internal/operations.sh']
        for path, mode in itertools.product(paths, ('submission-failure', 'terminal-failure', 'malformed-output', 'valid-output')):
            source = path.read_text()
            run_cloud = re.search(r'^run_cloud\(\) \{.*?^\}', source, re.M | re.S).group()
            decode = re.search(r'^decode_b64.*$', source, re.M).group()
            with self.subTest(skill=path.parents[2].name, mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest = root / 'manifest.json'
                manifest.write_text(json.dumps({'operationsStore': {'revision': 'r1'}}))
                log = root / 'calls'
                fake = root / 'aliyun'
                fake.write_text('#!/bin/sh\nexit 97\n'); fake.chmod(0o755)
                harness = '''source "$1"
operations_assert_current() { :; }
operations_checkpoint() { :; }
sleep() { :; }
aliyun_cli() {
  if [[ "$2" == RunCommand ]]; then
    printf 'submit\\n' >> "$CALL_LOG"
    if [[ "$MODE" == submission-failure ]]; then printf SECRET_REMOTE_CANARY; return 97; fi
    printf '{"InvokeId":"t-recorded"}'
  elif [[ "$MODE" == valid-output ]]; then
    printf '{"InvocationResults":{"InvocationResult":[{"Status":"Finished","ExitCode":0,"Output":"5bey5a6M5oiQ"}]}}'
  elif [[ "$MODE" == malformed-output ]]; then
    printf '{"InvocationResults":{"InvocationResult":[{"Status":"Finished","ExitCode":0,"Output":"!!!!"}]}}'
  else
    printf '{"InvocationResults":{"InvocationResult":[{"Status":"Failed","Output":"SECRET_REMOTE_CANARY"}]}}'
  fi
}
manifest=$2
region=cn-hangzhou
''' + decode + '\n' + run_cloud + '\nrun_cloud i-test true\n'
                env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'],
                           AUTOWONDER_PYTHON=sys.executable, MODE=mode, CALL_LOG=str(log))
                for attempt in range(1 if mode == 'valid-output' else 2):
                    result = subprocess.run(['bash', '-c', harness, '_', str(SCRIPTS / 'lib.sh'), str(manifest)],
                                            text=True, capture_output=True, env=env)
                    if mode == 'valid-output':
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertEqual('已完成', json.loads(result.stdout)['output'])
                    else:
                        self.assertNotEqual(0, result.returncode)
                    self.assertNotIn('SECRET_REMOTE_CANARY', result.stdout + result.stderr)
                self.assertEqual(['submit'], log.read_text().splitlines())
                evidence = json.loads(manifest.read_text())
                if mode == 'submission-failure':
                    self.assertEqual('unknown', evidence['remoteSubmission']['status'])
                else:
                    self.assertEqual('t-recorded', evidence['remoteInvocations'][0]['invokeId'])
                    self.assertEqual('finished' if mode == 'valid-output' else 'submitted',
                                     evidence['remoteInvocations'][0]['status'])
                    self.assertIsNone(evidence['remoteSubmission'])


@unittest.skipUnless(shutil.which('pwsh') or shutil.which('powershell'),
                     'PowerShell unavailable; native cloud adapter remains unverified')
class CloudAssistantPowerShellTests(unittest.TestCase):
    def run_ps(self, body, directory):
        script = Path(directory) / 'test.ps1'
        script.write_text("$ErrorActionPreference='Stop'\n. '" +
                          str(SCRIPTS / 'windows/cloud-assistant.ps1').replace("'", "''") +
                          "'\n" + body, encoding='utf-8')
        return subprocess.run([shutil.which('pwsh') or shutil.which('powershell'),
                               '-NoProfile', '-File', str(script)], text=True, capture_output=True,
                              env=dict(os.environ, AUTOWONDER_PYTHON=sys.executable))

    def test_native_adapter_decodes_current_response_and_preserves_write_ahead(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            manifest.write_text(json.dumps({'upgrade': {'remoteInvocations': []}}))
            result = self.run_ps("$manifest='" + str(manifest).replace("'", "''") + "'\n" + r"""
function Protect-CurrentUserFile { param($Path) }
function Import-AliyunCredential { param($Profile,$Region) }
function Start-Sleep { param($Seconds) }
function Invoke-AliyunJson {
    param($Product,$Action,$Profile,$Parameters)
    $saved = Read-JsonHashtable $manifest
    if ($Action -eq 'RunCommand') {
        if ($saved.remoteSubmission.status -ne 'unknown') { throw 'Missing write-ahead evidence' }
        return @{InvokeId='t-native'}
    }
    if ($saved.upgrade.remoteInvocations[0].invocationId -ne 't-native') { throw 'Missing invocation evidence' }
    return @{InvocationResults=@{InvocationResult=@(@{InvokeRecordStatus='Finished';ExitCode=0;Output='5bey5a6M5oiQ'})}}
}
Invoke-AutoWonderCloudCommand -ManifestData @{region='cn-hangzhou'} -InstanceId i-test -Script true -ManifestPath $manifest | ConvertTo-Json -Compress
""", directory)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('已完成', json.loads(result.stdout)['output'])
            entries = json.loads(manifest.read_text())['upgrade']['remoteInvocations']
            self.assertEqual(1, len(entries))
            self.assertEqual('finished', entries[0]['status'])

    def test_native_adapter_blocks_legacy_unknown_before_submission(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            manifest.write_text(json.dumps({'operationsStore': {'revision': 'r1'},
                'remoteInvocations': [{'invokeId': 't-old', 'status': 'poll-timeout'}]}))
            result = self.run_ps("$manifest='" + str(manifest).replace("'", "''") + "'\n" + r"""
function Import-AliyunCredential { param($Profile,$Region) }
function Invoke-AliyunJson { throw 'UNEXPECTED_SUBMIT' }
Invoke-AutoWonderCloudCommand -ManifestData @{region='cn-hangzhou'} -InstanceId i-test -Script true -ManifestPath $manifest
""", directory)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('unresolved', result.stderr)
            self.assertNotIn('UNEXPECTED_SUBMIT', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
