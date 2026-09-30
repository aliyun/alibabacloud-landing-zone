"""Execute the actual remote runner with a persistent fake database transport."""
import base64
import gzip
import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import time
import json
from pathlib import Path
import unittest
import test_windows_upgrade_execution as execution


class V075CheckpointTests(unittest.TestCase):
    setUp = execution.WindowsUpgradeExecutionTests.setUp
    tearDown = execution.WindowsUpgradeExecutionTests.tearDown
    run_payload = execution.WindowsUpgradeExecutionTests.run_payload
    mock_mysql = execution.WindowsUpgradeExecutionTests.mock_mysql
    migrations = execution.WindowsUpgradeExecutionTests.migrations

    def prepare_v075(self):
        self.mock_mysql()
        path = self.bin / 'mysql'
        source = path.read_text()
        source = source.replace("elif sql.startswith('INSERT INTO'):", "elif sql.startswith('SELECT error_message'):\n        print(ledger.get('75', {}).get('state', ''))\n    elif sql.startswith('INSERT INTO'):")
        source = source.replace("ledger[re.search(r'migration_version=(\\d+)',sql).group(1)]['success']=1;save()", "entry=ledger[re.search(r'migration_version=(\\d+)',sql).group(1)]\n        if 'success=1' in sql: entry['success']=1; entry['state']=None\n        else: entry['state']=re.search(r\"error_message='([^']+)'\",sql).group(1)\n        save()")
        source = source.replace("if 'FAIL' in content: sys.exit(1)", "if '-- D. 实际迁移' in content and (root/'fail-d').exists(): sys.exit(1)\n    if '-- A. 全量备份' in content and (root/'fail-ab').exists(): sys.exit(1)\n    if '-- C. dry-run' in content: print((root/'live-report').read_text())")
        source = source.replace("elif sql.startswith('UPDATE autowonder_schema_history'):", "elif sql.startswith('UPDATE autowonder_schema_history'):\n        if 'v075-review:' in sql and (root/'fail-report-ledger').exists(): sys.exit(1)\n        if 'success=1' in sql and (root/'fail-success-ledger').exists(): sys.exit(1)")
        path.write_text(source)
        self.migrations()
        name = 'V075__unify_status_kanban.sql'
        original = Path(__file__).resolve().parents[3] / 'docs/migration' / name
        migration = self.app / 'releases' / self.target[:12] / 'migration' / name
        migration.write_bytes(original.read_bytes())
        self.sha = hashlib.sha256(migration.read_bytes()).hexdigest()
        later = migration.parent / 'V076__later.sql'
        later.write_text('SELECT 76;')
        (self.root/'live-report').write_text('101\tCATEGORY_MATCH\n102\tREVIEW\n')
        (self.app/'maintenance-plan').write_text(self.plan)
        for name, body in {'systemctl': 'case "$*" in *ActiveState*) echo inactive;; *MainPID*) echo 0;; esac', 'ss': 'exit 0'}.items():
            tool=self.bin/name; tool.write_text('#!/bin/sh\n'+body+'\n'); tool.chmod(0o755)
        return {'maintenance': True, 'migrations': [{'version':75,'file':'docs/migration/'+migration.name,'sha256':self.sha}, {'version':76,'file':'docs/migration/'+later.name,'sha256':hashlib.sha256(later.read_bytes()).hexdigest()}]}

    def checkpoint(self, request):
        result = self.run_payload('database-migrate', request)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('V075_REVIEW_REQUIRED=', result.stdout)
        sha = next(line.split('=',1)[1] for line in result.stdout.splitlines() if line.startswith('V075_REVIEW_REQUIRED='))
        report = self.app/'migration-reports'/(sha+'.json')
        self.assertTrue(report.is_file())
        self.assertEqual(sha, hashlib.sha256(report.read_bytes()).hexdigest())
        self.assertEqual(0o600, report.stat().st_mode & 0o777)
        return sha, json.loads(report.read_text())

    def test_report_blocks_d_then_reviewed_continuation_applies_once_with_full_checksum(self):
        request=self.prepare_v075()
        sha, report=self.checkpoint(request)
        self.assertNotIn('-- D. 实际迁移', (self.root/'executed.log').read_text())
        self.assertNotIn('SELECT 76;', (self.root/'executed.log').read_text())
        self.assertEqual(self.plan, report['plan'])
        self.assertEqual(self.target, report['target'])
        self.assertEqual(self.sha, report['migrationSha256'])
        self.assertEqual({'host':'db','port':3306,'name':'test'}, report['database'])
        self.assertIn('CATEGORY_MATCH', report['output'])
        self.assertIn('REVIEW', report['output'])
        self.checkpoint(request)  # absent approval remains awaiting-review
        request['reviewedV075Report']=sha
        result=self.run_payload('database-migrate', request)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('MIGRATIONS_APPLIED=2',result.stdout)
        self.assertEqual(1,(self.root/'executed.log').read_text().count('SELECT 76;'))
        ledger=json.loads((self.root/'ledger.json').read_text())
        self.assertEqual({'sha':self.sha,'success':1,'state':None},ledger['75'])
        self.assertEqual(0,self.run_payload('database-migrate', request).returncode)
        self.assertEqual(1,(self.root/'executed.log').read_text().count('-- D. 实际迁移'))

    def test_stale_approval_live_drift_and_wrong_identity_fail_closed(self):
        request=self.prepare_v075(); sha,_=self.checkpoint(request)
        for change in ({'reviewedV075Report':'0'*64}, {'reviewedV075Report':sha,'plan':'f'*64}, {'reviewedV075Report':sha,'from':'f'*40}):
            result=self.run_payload('database-migrate',{**request,**change})
            self.assertNotEqual(0,result.returncode)
        (self.root/'live-report').write_text('changed live workitems')
        result=self.run_payload('database-migrate',{**request,'reviewedV075Report':sha})
        self.assertNotEqual(0,result.returncode)
        self.assertNotIn('-- D. 实际迁移',(self.root/'executed.log').read_text())

    def test_interrupted_d_cannot_resume_using_reviewed_report(self):
        request=self.prepare_v075();sha,_=self.checkpoint(request)
        (self.root/'fail-d').touch();request['reviewedV075Report']=sha
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        (self.root/'fail-d').unlink()
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        self.assertEqual(1,(self.root/'executed.log').read_text().count('-- D. 实际迁移'))

    def test_interruption_before_report_cannot_resume(self):
        request=self.prepare_v075();(self.root/'fail-ab').touch()
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        (self.root/'fail-ab').unlink()
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        self.assertFalse((self.app/'migration-reports').exists())

    def test_interruption_between_retaining_report_and_ready_ledger_requires_recovery(self):
        request=self.prepare_v075();(self.root/'fail-report-ledger').touch()
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        reports=list((self.app/'migration-reports').glob('*.json'))
        self.assertEqual(1,len(reports))
        (self.root/'fail-report-ledger').unlink()
        request['reviewedV075Report']=reports[0].stem
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        self.assertNotIn('-- D. 实际迁移',(self.root/'executed.log').read_text())

    def test_interruption_after_d_before_success_ledger_never_reexecutes_d(self):
        request=self.prepare_v075();sha,_=self.checkpoint(request)
        request['reviewedV075Report']=sha;(self.root/'fail-success-ledger').touch()
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        (self.root/'fail-success-ledger').unlink()
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        self.assertEqual(1,(self.root/'executed.log').read_text().count('-- D. 实际迁移'))

    def test_v075_requires_maintenance_even_when_caller_omits_it(self):
        request=self.prepare_v075();request['maintenance']=False
        self.assertNotEqual(0,self.run_payload('database-migrate',request).returncode)
        self.assertFalse((self.root/'ledger.json').exists())


class BashV075CheckpointTests(unittest.TestCase):
    def test_review_report_does_not_become_passed_activation_checkpoint(self):
        scripts=Path(__file__).resolve().parents[1]/'scripts'
        source=(scripts/'internal/operations.sh').read_text()
        body=source.split('  database-migrate)\n',1)[1].split('  rolling-upgrade)\n',1)[0]
        with tempfile.TemporaryDirectory() as directory:
            manifest=Path(directory)/'manifest.json'
            plan='c'*64; target='b'*40; digest='d'*64
            manifest.write_text(json.dumps({'mode':'upgrade','repositoryCommit':target,'resources':{'rds_instance_id':'rds'},'upgrade':{'planFingerprint':plan,'fromCommit':'a'*40,'toCommit':target,'executionMode':'maintenance','pendingMigrations':[{'version':75,'file':'docs/migration/V075__unify_status_kanban.sql','sha256':'e'*64}], 'databaseBackup':{'status':'verified','backupId':'backup','rdsInstanceId':'rds','planFingerprint':plan,'verifiedEpoch':time.time(),'completedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}}}))
            script='''set -euo pipefail
manifest=$1
UPGRADE_SKILL_DIR=$2
commit=$3
short_commit=${commit:0:12}
instances=(i-one)
confirm_migrations=true
confirm_rolling_compatible=false
reviewed_v075_report=
remote_db_prelude=
die() { echo "$*" >&2; exit 1; }
log() { echo "$*"; }
require_current_upgrade_staging() { :; }
verify_maintenance_nodes() { :; }
maintenance_check_script() { :; }
atomic_jq() { local file=$1; shift; jq "$@" "$file" > "$file.tmp" && mv "$file.tmp" "$file"; }
run_cloud() { printf %s "$2" > "$manifest.payload"; jq -n --arg output "V075_REVIEW_REQUIRED=$4
V075_REPORT_PATH=/opt/autowonder/migration-reports/$4.json" '{invocationId:"fixture",output:$output}'; }
'''.replace('$4',digest)+'case database-migrate in\n database-migrate)\n'+body+'esac\n'
            result=subprocess.run(['bash','-c',script,'bash',str(manifest),str(scripts.parent),target],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            checkpoint=json.loads(manifest.read_text())['upgrade']['databaseMigration']
            self.assertEqual('awaiting-review',checkpoint['status'])
            self.assertEqual(digest,checkpoint['reportSha256'])
            self.assertEqual(plan,checkpoint['planFingerprint'])
            wire=Path(str(manifest)+'.payload').read_text()
            packed=re.search(r'printf %s ([A-Za-z0-9+/=]+) \|',wire).group(1)
            payload=gzip.decompress(base64.b64decode(packed)).decode()
            self.assertIn('V075_REVIEW_REQUIRED=',payload)
            request=json.loads(base64.b64decode(re.search(r'base64.b64decode\("([A-Za-z0-9+/=]+)"\)',payload).group(1)))
            self.assertEqual(plan,request['plan'])
            self.assertTrue(request['maintenance'])
            self.assertEqual('',request['reviewedV075Report'])
            # The adapter may mark passed only after the explicit completion output.
            continuation=script.replace('reviewed_v075_report=\n','reviewed_v075_report='+digest+'\n').replace(
                'V075_REVIEW_REQUIRED='+digest+'\nV075_REPORT_PATH=/opt/autowonder/migration-reports/'+digest+'.json',
                'MIGRATIONS_APPLIED=1')
            result=subprocess.run(['bash','-c',continuation,'bash',str(manifest),str(scripts.parent),target],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual('passed',json.loads(manifest.read_text())['upgrade']['databaseMigration']['status'])

PWSH = os.environ.get('AUTOWONDER_TEST_PWSH') or shutil.which('pwsh') or shutil.which('powershell')


@unittest.skipUnless(PWSH, 'PowerShell unavailable; shared remote runner is executed by the Python fixtures')
class PowerShellV075CheckpointTests(unittest.TestCase):
    def test_report_is_awaiting_review_in_native_controller(self):
        scripts=Path(__file__).resolve().parents[1]/'scripts'
        source=(scripts/'upgrade-operations.ps1').read_text()
        body=source.split("    'database-migrate' {",1)[1].split("    'rolling-upgrade' {",1)[0]
        with tempfile.TemporaryDirectory() as directory:
            manifest=Path(directory)/'manifest.json'; script=Path(directory)/'test.ps1'
            plan='c'*64; digest='d'*64
            manifest.write_text(json.dumps({'resources':{'rds_instance_id':'rds'},'upgrade':{'planFingerprint':plan,'fromCommit':'a'*40,'toCommit':'b'*40,'executionMode':'maintenance','databaseCompatibility':{},'pendingMigrations':[{'version':75,'file':'docs/migration/V075__unify_status_kanban.sql','sha256':'e'*64}], 'databaseBackup':{'status':'verified','backupId':'backup','rdsInstanceId':'rds','planFingerprint':plan,'verifiedEpoch':int(time.time()),'completedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}}}))
            prefix = "$ErrorActionPreference='Stop'\n. '"+str(scripts/'windows-upgrade-common.ps1')+"'\n$Manifest='"+str(manifest)+"'\n"
            prefix += """function Protect-CurrentUserFile { param($Path) }
function Assert-UpgradeStaging { param($Data) }
function Refresh-ApprovedUpgradeTargets { param($Manifest) Get-ManifestData $Manifest }
function Test-AllMaintenanceNodes { param($Data) }
function Invoke-UpgradePayload { param($Data,$InstanceId,$Request,$ManifestPath) @{invocationId='fixture';output='V075_REVIEW_REQUIRED=DIGEST'} }
$data=Get-ManifestData $Manifest
$planFingerprint=$data.upgrade.planFingerprint
$instanceIds=@('i-one')
$ConfirmMigrations=$true
$ReviewedV075Report=''
$Operation='database-migrate'
switch ($Operation) {
    'database-migrate' {
""".replace('DIGEST',digest)
            script.write_text(prefix+body+'}\n',encoding='utf-8-sig')
            result=subprocess.run([PWSH,'-NoProfile','-File',str(script)],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            checkpoint=json.loads(manifest.read_text())['upgrade']['databaseMigration']
            self.assertEqual('awaiting-review',checkpoint['status'])
            self.assertEqual(digest,checkpoint['reportSha256'])


if __name__ == '__main__': unittest.main()
