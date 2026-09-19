"""PowerShell gates execute when a local runner is available; no downloads."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

SKILLS = Path(__file__).resolve().parents[2]
WINDOWS = SKILLS / 'upgrading-autowonder-on-alibaba-cloud/scripts/windows'
UPGRADE = SKILLS / 'upgrading-autowonder-on-alibaba-cloud/scripts'
PWSH = os.environ.get('AUTOWONDER_TEST_PWSH') or shutil.which('pwsh') or shutil.which('powershell')


class WindowsCompatibilityAudit(unittest.TestCase):
    def test_declared_powershell_51_paths_do_not_require_powershell_7(self):
        # A compatibility audit, not a replacement for the runtime tests below.
        for path in (WINDOWS / 'lib.ps1', WINDOWS / 'configure-terraform-acceleration.ps1'):
            source = path.read_text()
            self.assertNotIn('-AsHashtable', source, str(path))
            self.assertNotIn('-Encoding utf8NoBOM', source, str(path))


class SharedTargetFingerprintTests(unittest.TestCase):
    def test_target_fingerprint_binds_inventory_region_tags_and_verification_mode(self):
        data = {'deploymentId': 'test', 'region': 'cn-beijing',
                'resources': {'vpc_id': 'vpc-a', 'ecs_instance_ids': {'a': 'i-a'}},
                'tags': {'Environment': 'test'},
                'upgrade': {'targetVerification': {'nodes': [{'instanceId': 'i-a', 'vpcId': 'vpc-a'}]}}}
        material = b'{"deploymentId":"test","manifestInstanceIds":["i-a"],"nodes":[{"instanceId":"i-a","vpcId":"vpc-a"}],"region":"cn-beijing","tags":{"Environment":"test"},"vpcId":"vpc-a"}\n'
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            def fingerprint(value):
                manifest.write_text(json.dumps(value))
                result = subprocess.run([sys.executable, '-B', str(UPGRADE / 'upgrade_plan.py'),
                                         'target-fingerprint', '--manifest', str(manifest)],
                                        capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                return result.stdout.strip()
            original = fingerprint(data)
            self.assertEqual(hashlib.sha256(material).hexdigest(), original)
            for field in ('region', 'inventory', 'tags', 'mode'):
                changed = json.loads(json.dumps(data))
                if field == 'region': changed['region'] = 'cn-shanghai'
                if field == 'inventory': changed['resources']['ecs_instance_ids']['b'] = 'i-b'
                if field == 'tags': changed['tags']['Environment'] = 'prod'
                if field == 'mode': changed['upgradeInfo'] = {'tagVerificationMode': 'identity-only'}
                self.assertNotEqual(original, fingerprint(changed), field)


@unittest.skipUnless(PWSH, 'PowerShell runner unavailable: runtime and Windows ACL validation remain required')
class WindowsUpgradeGateRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.manifest = self.directory / 'manifest.json'

    def run_ps(self, body):
        script = self.directory / 'test.ps1'
        script.write_text("$ErrorActionPreference='Stop'\n. '" + str(WINDOWS / 'lib.ps1') + "'\n"
                          "function Protect-CurrentUserFile { param($Path) }\n" + body, encoding='utf-8-sig')
        return subprocess.run([PWSH, '-NoProfile', '-File', str(script)], capture_output=True, text=True)

    def fingerprint(self, command):
        result = subprocess.run([sys.executable, '-B', str(UPGRADE / 'upgrade_plan.py'), command,
                                 '--manifest', str(self.manifest)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def fixture(self):
        data = {'deploymentId': 'test', 'region': 'cn-beijing',
                'tags': {'Environment': 'test', 'Topology': 'single'},
                'resources': {'vpc_id': 'vpc-a', 'ecs_instance_ids': {'a': 'i-a'}},
                'upgrade': {'fromCommit': 'a' * 40, 'toCommit': 'b' * 40,
                    'blockedReasons': [], 'confirmationRequired': False,
                    'targetVerification': {'status': 'verified', 'verifiedEpoch': time.time(),
                        'nodes': [{'instanceId': 'i-a', 'vpcId': 'vpc-a'}],
                        'terraformInstanceIds': ['i-a'], 'cloudInstanceIds': ['i-a']}}}
        self.save(data)
        data['upgrade']['targetVerification']['fingerprint'] = self.fingerprint('target-fingerprint')
        self.save(data)
        fingerprint = self.fingerprint('fingerprint')
        data['upgrade']['planFingerprint'] = fingerprint
        data['upgrade']['approval'] = {'status': 'approved', 'planFingerprint': fingerprint}
        self.save(data)
        return data

    def save(self, data):
        self.manifest.write_text(json.dumps(data), encoding='utf-8')

    def check(self, function):
        return self.run_ps("$data=Get-ManifestData '" + str(self.manifest) + "'\n" + function + ' $data\n')

    def test_shell_approval_accepted_and_content_tampering_rejected(self):
        data = self.fixture()
        self.assertEqual(0, self.check('Assert-ApprovedUpgradePlan').returncode)
        data['upgrade']['toCommit'] = 'c' * 40
        self.save(data)
        self.assertNotEqual(0, self.check('Assert-ApprovedUpgradePlan').returncode)

    def test_checkpoint_rejects_expiration_inventory_and_region_changes(self):
        for mutation in ('expired', 'inventory', 'region', 'tags', 'nodes', 'future', 'invalid_epoch'):
            with self.subTest(mutation=mutation):
                data = self.fixture()
                self.assertEqual(0, self.check('Assert-VerifiedUpgradeTargets').returncode)
                if mutation == 'invalid_epoch': data['upgrade']['targetVerification']['verifiedEpoch'] = 'NaN'
                if mutation == 'expired': data['upgrade']['targetVerification']['verifiedEpoch'] -= 1801
                if mutation == 'future': data['upgrade']['targetVerification']['verifiedEpoch'] += 600
                if mutation == 'inventory': data['resources']['ecs_instance_ids']['b'] = 'i-b'
                if mutation == 'region': data['region'] = 'cn-shanghai'
                if mutation == 'tags': data['tags']['Environment'] = 'prod'
                if mutation == 'nodes': data['upgrade']['targetVerification']['nodes'] = []
                self.save(data)
                self.assertNotEqual(0, self.check('Assert-VerifiedUpgradeTargets').returncode)

    def test_refresh_uses_live_cloud_and_keeps_only_unchanged_approval(self):
        # Copy unmodified production code, replacing only OS ACL and cloud boundaries.
        copied_windows = self.directory / 'skills/upgrading-autowonder-on-alibaba-cloud/scripts/windows'
        copied_upgrade = self.directory / 'skills/upgrading-autowonder-on-alibaba-cloud/scripts'
        copied_windows.mkdir(parents=True)
        copied_upgrade.mkdir(parents=True, exist_ok=True)
        for name in ('verify-deployment-targets.ps1', 'ecs_inventory.py', 'upgrade_plan.py', 'approve-upgrade-plan.ps1'):
            shutil.copy2(UPGRADE / name, copied_upgrade / name)
        cloud_file = self.directory / 'cloud.json'
        boundaries = """
function Protect-CurrentUserFile { param($Path) }
function Ensure-AutoWonderAliyunProfile { param($Region, $ExpectedAccountId) }
function Invoke-AliyunJson {
    param($Product, $Action, $Profile, $Parameters)
    if ($Product -ne 'ecs' -or $Action -ne 'DescribeInstances' -or $Profile -ne 'auto-wonder' -or $Parameters.RegionId -ne 'cn-beijing') { throw 'Unexpected cloud request' }
    $fixture = Read-JsonHashtable -Path '__CLOUD__'
    if ($Parameters.ContainsKey('InstanceIds')) {
        if ($Parameters.InstanceIds -ne '["i-a"]') { throw 'Unexpected requested inventory' }
        return @{Instances=@{Instance=@($fixture['node'])}}
    }
    if ($Parameters['Tag.2.Value'] -ne 'test') { throw 'Missing deployment filter' }
    return @{Instances=@{Instance=@($fixture['node']) + @($fixture['extra'])}; TotalCount=(1 + @($fixture['extra']).Count)}
}
""".replace('__CLOUD__', str(cloud_file))
        (copied_windows / 'lib.ps1').write_text((WINDOWS / 'lib.ps1').read_text() + boundaries)
        data = self.fixture()
        node = {'InstanceId': 'i-a', 'RegionId': 'cn-beijing', 'VpcAttributes': {'VpcId': 'vpc-a'},
                'Tags': {'Tag': [{'TagKey': key, 'TagValue': value} for key, value in
                    {'Project': 'AutoWonder', 'DeploymentId': 'test', 'ManagedBy': 'Terraform',
                     'Environment': 'test', 'Topology': 'single'}.items()]}}
        cloud_file.write_text(json.dumps({'node': node, 'extra': []}))
        command = ". '" + str(copied_windows / 'lib.ps1') + "'\nRefresh-ApprovedUpgradeTargets -Manifest '" + str(self.manifest) + "' | Out-Null\n"
        result = self.run_ps(command)
        self.assertEqual(0, result.returncode, result.stderr)
        refreshed = json.loads(self.manifest.read_text())
        self.assertEqual(data['upgrade']['approval'], refreshed['upgrade']['approval'])
        self.assertGreaterEqual(refreshed['upgrade']['targetVerification']['verifiedEpoch'], data['upgrade']['targetVerification']['verifiedEpoch'])
        checkpoint_before_malformed_response = self.manifest.read_bytes()
        cloud_file.write_text(json.dumps({'node': node, 'extra': [None]}))
        result = self.run_ps(command)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(checkpoint_before_malformed_response, self.manifest.read_bytes())
        cloud_file.write_text(json.dumps({'node': node, 'extra': [{'InstanceId': 'i-unexpected'}]}))
        result = self.run_ps(command)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('inventory', result.stderr.lower())

    def test_backup_requires_current_plan_identity_and_verified_node_hashes(self):
        for mutation in ('plan', 'from', 'target', 'status', 'hash'):
            with self.subTest(mutation=mutation):
                data = self.fixture()
                upgrade = data['upgrade']
                upgrade['backup'] = {'status': 'verified', 'planFingerprint': upgrade['planFingerprint'],
                    'fromCommit': upgrade['fromCommit'], 'targetCommit': upgrade['toCommit'],
                    'nodes': [{'instanceId': 'i-a', 'status': 'verified', 'sha256': 'd' * 64}]}
                self.save(data)
                self.assertEqual(0, self.check('Assert-UpgradeBackupCoverage').returncode)
                backup = upgrade['backup']
                if mutation == 'plan': backup['planFingerprint'] = 'e' * 64
                if mutation == 'from': backup['fromCommit'] = 'e' * 40
                if mutation == 'target': backup['targetCommit'] = 'e' * 40
                if mutation == 'status': backup['nodes'][0]['status'] = 'pending'
                if mutation == 'hash': backup['nodes'][0]['sha256'] = 'invalid'
                self.save(data)
                self.assertNotEqual(0, self.check('Assert-UpgradeBackupCoverage').returncode)

    def test_native_cli_preserves_json_quotes_spaces_empty_and_trailing_backslash(self):
        executable = sys.executable.replace("'", "''")
        result = self.run_ps("function Get-AliyunExecutable { return '" + executable + "' }\n"
            + r"""$params=@{InstanceIds='["i-a","i-b"]';Path='C:\some folder\';Empty='';CommandContent='echo "literal"'}""" + "\n"
            + "Invoke-AliyunFlat -Product '-c' -Action 'import json,sys;print(json.dumps(sys.argv[1:]))' -Profile auto-wonder -Parameters $params\n")
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = json.loads(result.stdout)
        self.assertEqual(['--profile', 'auto-wonder'], arguments[:2])
        parameters = dict(zip(arguments[2::2], arguments[3::2]))
        self.assertEqual('["i-a","i-b"]', parameters['--InstanceIds'])
        self.assertEqual('C:\\some folder\\', parameters['--Path'])
        self.assertEqual('', parameters['--Empty'])
        self.assertEqual('echo "literal"', parameters['--CommandContent'])

    def test_cloud_invocation_evidence_survives_terminal_failure(self):
        self.save({'upgrade': {}})
        result = self.run_ps(". '" + str(WINDOWS / 'cloud-assistant.ps1') + "'\n"
            "function Protect-CurrentUserFile { param($Path) }\n"
            "function Import-AliyunCredential { param($Profile,$Region) }\n"
            "function Start-Sleep { param($Seconds) }\n"
            "function Invoke-AliyunJson { param($Product,$Action,$Profile,$Parameters)\n"
            "if ($Action -eq 'RunCommand') { return @{InvokeId='inv-test'} }\n"
            "return @{InvocationResults=@{InvocationResult=@(@{InvocationStatus='Failed';Output='SECRET_REMOTE_CANARY'})}} }\n"
            "Invoke-AutoWonderCloudCommand -ManifestData @{region='cn-beijing'} -InstanceId i-a -Script 'true' -ManifestPath '"
            + str(self.manifest) + "' -Operation backup\n")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('SECRET_REMOTE_CANARY', result.stdout + result.stderr)
        evidence = json.loads(self.manifest.read_text())['upgrade']['remoteInvocations']
        self.assertEqual(1, len(evidence))
        self.assertEqual('inv-test', evidence[0]['invocationId'])
        self.assertEqual('failed', evidence[0]['status'])
        self.assertEqual('backup', evidence[0]['operation'])
        self.assertNotIn('output', evidence[0])

    def test_cloud_failure_does_not_echo_secret_output(self):
        result = self.run_ps("function Invoke-AliyunFlat { throw 'SECRET_CANARY_9841' }\n"
                             "Invoke-AliyunJson -Product ecs -Action RunCommand -Profile auto-wonder\n")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('SECRET_CANARY_9841', result.stdout + result.stderr)
        self.assertIn('Alibaba Cloud API response failed', result.stderr)

    @unittest.skipUnless(os.name == 'nt', 'Windows host required for real ACL validation')
    def test_real_windows_acl_removes_inherited_access(self):
        self.save({'safe': 'value'})
        result = self.run_ps(". '" + str(WINDOWS / 'lib.ps1') + "'\n"
            "Protect-CurrentUserFile -Path '" + str(self.manifest) + "'\n"
            "$acl=Get-Acl -LiteralPath '" + str(self.manifest) + "'\n"
            "if (-not $acl.AreAccessRulesProtected) { throw 'ACL inheritance remains enabled' }\n"
            "$identity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name\n"
            "if (@($acl.Access | Where-Object { $_.IdentityReference.Value -ne $identity }).Count -ne 0) { throw 'Unexpected file access rule' }\n")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_optional_fields_and_nested_json_update(self):
        self.save({'nested': {'items': [{'name': '中文'}]}, 'nullValue': None})
        result = self.run_ps("function Protect-CurrentUserFile { param($Path) }\n"
            "Update-JsonFileAtomic '" + str(self.manifest) + "' { param($d) $d.nested.items[0].name='完成'; $d }\n"
            "$d=Get-ManifestData '" + str(self.manifest) + "'\n"
            "if ($null -ne $d['missing']) { throw 'missing key' }\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('完成', json.loads(self.manifest.read_text())['nested']['items'][0]['name'])
        self.assertFalse(self.manifest.read_bytes().startswith(b'\xef\xbb\xbf'))


if __name__ == '__main__':
    unittest.main()
