import importlib.util
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path
import unittest
from test_windows_upgrade_execution import WindowsUpgradeExecutionTests

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
spec = importlib.util.spec_from_file_location('migration_policy', SCRIPTS / 'migration_policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class MigrationPolicyTests(unittest.TestCase):
    def test_index_replacement_is_maintenance_without_data_destruction(self):
        risk = policy.classify_sql('ALTER TABLE delivery DROP INDEX uk_old, ADD UNIQUE KEY uk_old (id, retry);')
        self.assertTrue(risk['maintenanceRequired'])
        self.assertFalse(risk['destructive'])

    def test_real_drop_cannot_hide_behind_index_drop(self):
        risk = policy.classify_sql('ALTER TABLE delivery DROP INDEX uk_old, DROP COLUMN data;')
        self.assertTrue(risk['destructive'])
        self.assertTrue(risk['maintenanceRequired'])

    def test_literals_and_comments_do_not_change_risk(self):
        risk = policy.classify_sql("-- DROP TABLE user;\nINSERT INTO t VALUES ('DROP TABLE user'); /* TRUNCATE */")
        self.assertFalse(risk['destructive'])
        self.assertEqual(['INSERT'], risk['riskOperations'])


class StagingCheckpointTests(unittest.TestCase):
    def test_interrupted_resume_retains_other_passed_nodes(self):
        source=(SCRIPTS/'internal/operations.sh').read_text()
        rollout=source.split('  rolling-upgrade)',1)[1]
        seed=re.search(r"    node_json=\$\(jq -c '[\s\S]*?' \"\$manifest\"\)",rollout).group(0)
        merge=re.search(r'      node_json=\$\(jq --arg id[\s\S]*?<<<"\$node_json"\)',rollout).group(0)
        document={'upgrade':{'planFingerprint':'plan','toCommit':'target'},'resources':{'ecs_instance_ids':['A','B','C']},'rollingUpgrade':{'planFingerprint':'plan','targetCommit':'target','nodes':[{'instanceId':'A','status':'passed'},{'instanceId':'B','status':'passed'}]}}
        with tempfile.TemporaryDirectory() as temporary:
            manifest=Path(temporary)/'manifest.json';manifest.write_text(json.dumps(document))
            script='manifest="$1"; instance=A; invocation=new; previous=target; rollout_status=passed; resolution_required=;\n'+seed+'\n'+merge+'\nprintf "%s" "$node_json"\n'
            result=subprocess.run(['bash','-c',script,'bash',str(manifest)],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            nodes=json.loads(result.stdout)
            self.assertEqual(['A','B'],sorted(n['instanceId'] for n in nodes))
            self.assertEqual(1,sum(n['instanceId']=='A' for n in nodes))
            document['rollingUpgrade']['nodes']=nodes;manifest.write_text(json.dumps(document))
            repeated=subprocess.run(['bash','-c',script,'bash',str(manifest)],capture_output=True,text=True)
            self.assertEqual(['A','B'],sorted(n['instanceId'] for n in json.loads(repeated.stdout)))

    def test_powershell_first_rollout_uses_optional_dictionary_access(self):
        source=(SCRIPTS/'upgrade-operations.ps1').read_text()
        self.assertIn("$rollout=$Data['rollingUpgrade']",source)
        self.assertIn("$previousRollout=$data['rollingUpgrade']",source)
        self.assertIn("$null -ne $previousRollout",source)
        self.assertIn("$nodes=@($previousRollout['nodes']",source)
        self.assertIn("$nodes=@($nodes | Where-Object { $_.instanceId -ne $instanceId })",source)

    def test_old_stage_cannot_authorize_new_plan_or_target_set(self):
        plan='a'*64; env='b'*64; jar='c'*64
        document=dict(repositoryCommit='d'*40, artifacts={'jar':{'sha256':jar},'systemdUnit':{'sha256':'e'*64}},
            resources={'ecs_instance_ids':['i-one','i-two']},
            upgrade=dict(planFingerprint=plan,toCommit='d'*40,environmentCandidateSha256=env,targetRecommendedRuntimeVersion='1.2.3'),
            runtimeConfig=dict(prepared=True,planFingerprint=plan,envSha256=env,recommendedRuntimeVersion='1.2.3'),
            deployment={'lastRun':dict(mode='stage-only',planFingerprint=plan,targetCommit='d'*40,jarSha256=jar,unitSha256='e'*64,envSha256=env,instanceIds=['i-one','i-two'])})
        with tempfile.TemporaryDirectory() as temporary:
            manifest=Path(temporary)/'manifest.json'
            def check():
                manifest.write_text(json.dumps(document))
                return subprocess.run(['bash','-c','die() { exit 1; }; source "$1"; require_current_upgrade_staging "$2"','bash',str(SCRIPTS/'upgrade-lib.sh'),str(manifest)],capture_output=True).returncode
            self.assertEqual(0,check())
            document['deployment']['lastRun']['planFingerprint']='f'*64
            self.assertNotEqual(0,check())
            document['deployment']['lastRun']['planFingerprint']=plan
            document['deployment']['lastRun']['instanceIds']=['i-one']
            self.assertNotEqual(0,check())


class MaintenanceRemoteTests(WindowsUpgradeExecutionTests):
    def mock_stopped(self, active='inactive', listener=''):
        systemctl = self.bin / 'systemctl'
        systemctl.write_text('#!/bin/sh\ncase "$*" in\n"show -p ActiveState --value autowonder.service") echo '+active+';;\n"show -p MainPID --value autowonder.service") echo 0;;\nesac\n')
        systemctl.chmod(0o755)
        ss = self.bin / 'ss'
        ss.write_text('#!/bin/sh\nprintf %s "'+listener+'"\n')
        ss.chmod(0o755)

    def test_passed_node_resume_verifies_target_without_restart(self):
        request=self.prepare_stage()
        staged=self.run_payload('stage-upgrade',request,mock_system=True)
        self.assertEqual(0,staged.returncode,staged.stderr)
        target=self.app/'releases'/self.target[:12]
        (self.app/'current').unlink();(self.app/'current').symlink_to(target)
        (self.app/'maintenance-plan').write_text(self.plan)
        self.mock_services()
        systemctl=self.bin/'systemctl'
        systemctl.write_text('#!/bin/sh\nif [ "$1" = restart ]; then exit 91; fi\nexit 0\n')
        request.update(maintenance=True,resumePassed=True,jarSha=hashlib.sha256((target/'auto-wonder.jar').read_bytes()).hexdigest(),unitSha=hashlib.sha256((self.units/'autowonder.service').read_bytes()).hexdigest())
        result=self.run_payload('rolling-upgrade',request)
        self.assertEqual(0,result.returncode,result.stderr)
        (self.app/'current').unlink();(self.app/'current').symlink_to(self.app/'releases'/self.old)
        result=self.run_payload('rolling-upgrade',request)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('no longer runs the target',result.stderr)

    def test_stop_persists_plan_and_checks_process(self):
        self.mock_stopped()
        result=self.run_payload('maintenance-stop')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(self.plan,(self.app/'maintenance-plan').read_text().strip())
        self.assertIn('MAINTENANCE_STATUS=stopped',result.stdout)

    def test_restarted_node_blocks_migration_before_database_access(self):
        self.mock_stopped(active='active')
        (self.app/'maintenance-plan').write_text(self.plan)
        result=self.run_payload('database-migrate',{'maintenance':True,'migrations':[]})
        self.assertNotEqual(0,result.returncode)
        self.assertIn('inactive service',result.stderr)

    def test_wrong_plan_or_listener_blocks_activation(self):
        self.mock_stopped(listener='LISTEN')
        (self.app/'maintenance-plan').write_text(self.plan)
        result=self.run_payload('rolling-upgrade',{'maintenance':True})
        self.assertNotEqual(0,result.returncode)
        self.assertIn('listener remains active',result.stderr)
        (self.app/'maintenance-plan').write_text('f'*64)
        result=self.run_payload('maintenance-verify')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('plan marker mismatch',result.stderr)

if __name__ == '__main__':
    unittest.main()
