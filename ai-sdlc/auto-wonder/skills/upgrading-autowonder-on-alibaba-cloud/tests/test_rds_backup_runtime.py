import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'

class RdsBackupTests(unittest.TestCase):
    def run_backup(self, pages):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            shutil.copy(SCRIPTS / 'verify-rds-backup.sh', root)
            (root / 'upgrade-lib.sh').write_text(f'''source "{SCRIPTS}/lib.sh"
refresh_target_verification() {{ :; }}
configure_cloud_profile() {{ :; }}
reject_secret_keys() {{ :; }}
aliyun_cli() {{ local page=1; while (($#)); do if [[ $1 == --PageNumber ]]; then page=$2; fi; shift; done; cat "$FIXTURE_ROOT/page$page.json"; }}
''')
            for i, page in enumerate(pages, 1):
                (root / f'page{i}.json').write_text(json.dumps({'Items': {'Backup': page}, 'TotalRecordCount': 100 * (len(pages)-1)+len(pages[-1])}))
            (root / 'aliyun').write_text('#!/bin/sh\nexit 0\n')
            (root / 'aliyun').chmod(0o700)
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'deploymentId':'test','region':'cn-test','resources':{'rds_instance_id':'rm-test'},'upgrade':{'planFingerprint':'a'*64}}))
            result = subprocess.run(['bash', str(root/'verify-rds-backup.sh'), '--manifest', str(manifest)], capture_output=True, text=True, env={**os.environ, 'FIXTURE_ROOT':str(root),'PATH':str(root)+os.pathsep+os.environ['PATH']})
            return result, json.loads(manifest.read_text())

    def entry(self, days, identity='valid'):
        completed = dt.datetime.now(dt.timezone.utc)-dt.timedelta(days=days)
        return {'BackupId': identity, 'BackupStatus':'Success','BackupEndTime':completed.strftime('%Y-%m-%dT%H:%M:%SZ')}

    def test_paginates_and_binds_to_current_plan(self):
        result, manifest = self.run_backup([[self.entry(9, 'old')], [self.entry(1)]])
        self.assertEqual(0,result.returncode,result.stderr)
        backup = manifest['upgrade']['databaseBackup']
        self.assertEqual('valid',backup['backupId'])
        self.assertEqual('a'*64,backup['planFingerprint'])
        self.assertEqual('rm-test',backup['rdsInstanceId'])

    def test_rejects_future_and_stale_completion(self):
        for days in (-1, 8):
            result, manifest = self.run_backup([[self.entry(days)]])
            self.assertNotEqual(0,result.returncode)
            self.assertIn('no successful RDS backup',result.stderr)
            self.assertNotIn('databaseBackup',manifest['upgrade'])

    def test_migration_gate_rejects_future_stale_and_wrong_plan(self):
        text = (SCRIPTS/'internal/operations.sh').read_text()
        start = text.index("    jq -e '\n      .upgrade.databaseBackup.status") + len("    jq -e '")
        query = text[start:text.index("' \"$manifest\"", start)]
        now = dt.datetime.now(dt.timezone.utc)
        base = {'resources':{'rds_instance_id':'rm-test'},'upgrade':{'planFingerprint':'a'*64,'databaseBackup':{'status':'verified','backupId':'1','rdsInstanceId':'rm-test','planFingerprint':'a'*64,'verifiedEpoch':now.timestamp(),'completedAt':(now-dt.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')}}}
        for field, value in [('planFingerprint','b'*64), ('verifiedEpoch',now.timestamp()+3600), ('verifiedEpoch',now.timestamp()-90000), ('completedAt',(now+dt.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')), ('completedAt',(now-dt.timedelta(days=8)).strftime('%Y-%m-%dT%H:%M:%SZ'))]:
            data=json.loads(json.dumps(base));data['upgrade']['databaseBackup'][field]=value
            result=subprocess.run(['jq','-e',query],input=json.dumps(data),capture_output=True,text=True)
            self.assertEqual(1,result.returncode,(field,result.stderr))
        self.assertEqual(0,subprocess.run(['jq','-e',query],input=json.dumps(base),capture_output=True,text=True).returncode)

class RuntimeRestoreTests(unittest.TestCase):
    def test_python_wrapper_is_not_probed_before_safety_gates(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);scripts=root/'skills/upgrading-autowonder-on-alibaba-cloud/scripts';scripts.mkdir(parents=True)
            shutil.copy(SCRIPTS/'runtime-env.sh',scripts)
            platform='darwin' if os.uname().sysname=='Darwin' else 'linux'
            arch='arm64' if os.uname().machine in ('arm64','aarch64') else 'x86_64'
            target=root/f'skills/.autowonder-tools/python/1/{platform}-{arch}';target.mkdir(parents=True)
            binary=target/'python3';binary.write_text('#!/bin/sh\necho pinned\n');binary.chmod(0o700)
            (target/'.verified').write_text('a'*64+'\n'+hashlib.sha256(binary.read_bytes()).hexdigest()+'\n')
            (scripts/'runtime-lock.tsv').write_text(f'python\t{platform}\t{arch}\t1\thttps://unused\t'+'a'*64+'\traw\tpython3\n')
            mock=root/'bin';mock.mkdir();(mock/'python3').write_text('#!/bin/sh\necho unexpected-probe\nexit 1\n');(mock/'python3').chmod(0o700)
            result=subprocess.run(['bash','-c','source "$1"; autowonder_restore_runtime_environment; command -v python3','fixture',str(scripts/'runtime-env.sh')],capture_output=True,text=True,env={**os.environ,'PATH':str(mock)+os.pathsep+os.environ['PATH']})
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual(str(mock/'python3'),result.stdout.strip())

    def test_restores_verified_missing_tool_without_shadowing_path(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            scripts=root/'skills/upgrading-autowonder-on-alibaba-cloud/scripts'
            scripts.mkdir(parents=True)
            shutil.copy(SCRIPTS/'runtime-env.sh',scripts)
            platform = 'darwin' if os.uname().sysname=='Darwin' else 'linux'
            arch = 'arm64' if os.uname().machine in ('arm64','aarch64') else 'x86_64'
            target=root/f'skills/.autowonder-tools/ossutil/1/{platform}-{arch}'
            target.mkdir(parents=True)
            binary=target/'ossutil';binary.write_text('#!/bin/sh\necho restored\n');binary.chmod(0o700)
            (target/'.verified').write_text('a'*64+'\n'+hashlib.sha256(binary.read_bytes()).hexdigest()+'\n')
            (scripts/'runtime-lock.tsv').write_text(f'ossutil\t{platform}\t{arch}\t1\thttps://unused\t'+ 'a'*64 +'\traw\tossutil\n')
            result=subprocess.run(['bash','-c','source "$1"; autowonder_restore_runtime_environment; ossutil', 'fixture', str(scripts/'runtime-env.sh')], capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual('restored',result.stdout.strip())
            # A separate public phase must restore before its operations-store check.
            shutil.copy(SCRIPTS/'lib.sh',scripts)
            (scripts/'operations-store.py').write_text("import subprocess; subprocess.run(['ossutil'],check=True)\n")
            manifest=root/'manifest.json';manifest.write_text('{"operationsStore":{}}')
            checked=subprocess.run(['bash','-c','source "$1"; json_validate "$2"', 'fixture',str(scripts/'lib.sh'),str(manifest)],capture_output=True,text=True)
            self.assertEqual(0,checked.returncode,checked.stderr)
            mock=root/'bin';mock.mkdir()
            (mock/'ossutil').write_text('#!/bin/sh\necho caller\n');(mock/'ossutil').chmod(0o700)
            preserved=subprocess.run(['bash','-c','source "$1"; autowonder_restore_runtime_environment; ossutil','fixture',str(scripts/'runtime-env.sh')],capture_output=True,text=True,env={**os.environ,'PATH':str(mock)+os.pathsep+os.environ['PATH']})
            self.assertEqual('caller',preserved.stdout.strip())
            binary.write_text('#!/bin/sh\necho tampered\n')
            rejected=subprocess.run(['bash','-c','source "$1"; autowonder_restore_runtime_environment; ossutil','fixture',str(scripts/'runtime-env.sh')],capture_output=True,text=True)
            self.assertNotEqual(0,rejected.returncode)
            self.assertNotIn('tampered',rejected.stdout)

if __name__ == '__main__': unittest.main()
