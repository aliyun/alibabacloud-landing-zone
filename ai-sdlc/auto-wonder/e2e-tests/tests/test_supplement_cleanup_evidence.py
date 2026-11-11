import importlib.util
from pathlib import Path
import unittest
import copy
import hashlib
import json
import os
import subprocess
import tempfile
from unittest.mock import patch

class InitialContract(unittest.TestCase):
    def test_only_chief_raw_supplement_stdout_can_be_empty_with_exact_empty_hash(self):
        empty={'sha256':self.m.sha(b''),'bytes':0}
        self.m.provenance_ref_check(empty,scene='chief-official-config-refresh',group='raw',role='supplement-stdout')
        for role in ('native-stdout','context.json','scene.json','native-report.json','chief-invocation',
                     'chief-checkpoint','chief-release-journal','chief-fixture-plan','lifecycle-result'):
            with self.subTest(role=role),self.assertRaises(ValueError):
                self.m.provenance_ref_check(empty,scene='chief-official-config-refresh',group='raw',role=role)
        for scene,group,role in [('two-step','raw','supplement-stdout'),
                                 ('chief-official-config-refresh','tools','supplement-stdout'),
                                 ('chief-official-config-refresh','package','metadata'),
                                 ('chief-official-config-refresh','package','binary')]:
            with self.subTest(scene=scene,group=group,role=role),self.assertRaises(ValueError):
                self.m.provenance_ref_check(empty,scene=scene,group=group,role=role)
        for invalid in [dict(empty,sha256='a'*64),dict(empty,bytes=-1),dict(empty,bytes=True),
                        dict(empty,bytes=134217729),dict(empty,sha256='E'*64)]:
            with self.subTest(invalid=invalid),self.assertRaises(ValueError):
                self.m.provenance_ref_check(invalid,scene='chief-official-config-refresh',group='raw',role='supplement-stdout')

    def test_standard_public_runtime_context_0644_is_read_without_mutation(self):
        path=self.state/'runtime.json';before=path.read_bytes();path.chmod(0o644)
        self.assertEqual(2,len(self.validate()))
        self.assertEqual(before,path.read_bytes());self.assertEqual(0o644,path.stat().st_mode&0o777)

    def test_private_bundle_and_original_native_report_still_require_0600(self):
        for path in (self.bundle/'proof.json',self.bundle/'scene.json',self.state/'runtime-dispatch.json'):
            path.chmod(0o644)
            with self.subTest(name=path.name),self.assertRaises(ValueError):self.validate()
            path.chmod(0o600)

    def test_chief_deep_contract_requires_correct_legacy_loaded_digest(self):
        r,w,inv,cp,j,e=chief_contract_fixture(self.m)
        p={'schemaVersion':1,'report':r,'wrapper':w,'invocation':inv,'checkpoint':cp,'journal':j}
        self.assertEqual(5,self.m.chief_validate_chief_projection(p,e)['executorId'])
        j[3]['jarSha']='0'*64
        with self.assertRaises(ValueError):self.m.chief_validate_chief_projection(p,e)

    def test_failed_chief_even_if_restored_or_wrong_turn_audit_never_qualifies(self):
        r,w,inv,cp,j,e=chief_contract_fixture(self.m)
        p={'schemaVersion':1,'report':r,'wrapper':w,'invocation':inv,'checkpoint':cp,'journal':j}
        r['verdict']='FAIL'
        with self.assertRaises(ValueError):self.m.chief_validate_chief_projection(p,e)
        r['verdict']='PASS';r['dialogue']['oldTurns'][0]['toolAudit']['scope']['executorId']=99
        with self.assertRaises(ValueError):self.m.chief_validate_chief_projection(p,e)

    def setUp(self):
        path=Path(__file__).parents[1]/'lib/supplement_cleanup_evidence.py'
        spec=importlib.util.spec_from_file_location('cleanup_proof',path)
        self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.state=Path(self.tmp.name).resolve();self.state.chmod(0o700)
        self.app=self.state/'stdout.log';self.file=self.state/'file.log'
        self.root=self.state/'supplement-cleanup-evidence';self.root.mkdir(mode=0o700)
        self.bundle=self.root/'two-step-one';self.bundle.mkdir(mode=0o700)
        self.identity={'commit':'a'*40,'tree':'b'*40,'dirty':False}
        self.context={'runId':self.state.name,'git':{'commit':'a'*40,'dirty':False},'logs':{'springBootConsole':str(self.app),'springBootFile':str(self.file)}}
        self.eof=b'2026-11-10 17:51:54,603|WARN|||ExecutorWsEndpoint|thread|WS error executorId=45 java.io.EOFException \n'
        self.delete=b'2026-11-10 17:51:55,603|INFO|12345678-1234-1234-1234-123456789abc|DELETE /api/executors/45|BizLoggerFilter|thread|Response finished requestPath=/api/executors/45 status=200 elapsedMs=15 \n'
        self.prefix=b'2026-11-10 17:51:50,000|INFO|||Other|thread|prefix\n'
        for p in (self.app,self.file):p.write_bytes(self.prefix+self.eof+self.delete);p.chmod(0o600)
        self.write(self.state/'runtime.json',self.context)
        self.write(self.state/'result.json',{'verdict':'PASS'})
        self.scene={'scene':'runtime.real-qoder-delivery','sourceCommit':'a'*40,'sourceScenarioCommit':self.m.MASTER,
            'verdict':'PASS','phase':'complete','runtimeVersion':'0.3.43','processCleanup':True,
            'ownedExecutorDeletedAfterAssertions':True,'checks':sorted(self.m.CHECKS),'realModel':True,
            'executorId':45,'workspaceId':1,'workitemId':2,'dispatchId':3,
            'cleanupProcessProof':{'originalRuntimeStopCompleted':True,'processLeaderExited':True,'processGroupEmpty':True},
            'cleanupLogStart':{'stdout':1,'file':1},'cleanupLogEnd':{'stdout':3,'file':3},
            'cleanupOrder':{'processStoppedBeforeDelete':True,'deleteCallCompleted':True,
                'deleteEvidenceSource':'server-log-only-not-client-status',
                'processIdentity':{'pid':123,'pgid':123,'sid':123,'startTicks':456,'uid':0}}}
        self.native_report={'verdict':'PASS','cleanup':True,'checks':sorted(self.m.NATIVE),'executorId':44,'workspaceId':4,'workitemId':5,'dispatchId':6,'artifactId':7,
            'agentId':8,'agentVersionId':9,'stepId':10,'artifactSha256':'a'*64,'runtimeVersion':'0.3.43','sourceRuntimeVersion':'0.3.43'}
        self.tool_data={name:('fixture-'+name).encode()for name in self.m.TOOL_NAMES}
        self.tools={name:{'sha256':self.m.sha(data),'bytes':len(data)}for name,data in self.tool_data.items()}
        self.metadata=json.dumps({'name':'@alicloud/autowonder','version':'0.3.43'}).encode();self.binary=b'fixture-not-executable'
        self.stdout=b'fixture actual child stdout\n'
        base={'source':self.identity,'actualExitCode':0,'passed':True,'interruptedOrTimedOut':False,
              'ownedContainerResidue':False,'image':self.m.IMAGE,'stateDir':str(self.state),'runtimeVersion':'0.3.43',
              'postSource':self.identity,'modelExecution':True,'metadataSha256':self.m.sha(self.metadata),
              'nativeBinarySha256':self.m.sha(self.binary),'logSha256':self.m.sha(self.stdout)}
        self.native=dict(base,mode='original-verify-check-with-runtime',lifecycleVerdict='PASS',
            harnessSha256=self.tools['harness/run.py']['sha256'],entrypointSha256=self.tools['harness/entrypoint.py']['sha256'])
        self.wrapper=dict(base,mode='supplemental-two-step-real-qoder',postSource=self.identity,
            protectedInputsUnchanged=True,nativePrerequisiteValidated=True,sourceScenarioCommit=self.m.MASTER,
            acceptanceVerdict='PASS',supplementVerdict='PASS',nativeHarnessSha256=self.tools['harness/run.py']['sha256'],
            relaySha256=self.tools['harness/entrypoint.py']['sha256'],supplementFileSha256={key:self.tools[key]['sha256']for key in self.m.TOOL_FILES})
        self.refresh()

    def write(self,path,value):
        path.write_text(json.dumps(value,sort_keys=True));path.chmod(0o600)

    def refresh(self):
        self.write(self.bundle/'scene.json',self.scene);self.write(self.bundle/'context.json',self.context)
        self.write(self.bundle/'native-report.json',self.native_report)
        self.write(self.state/'runtime-dispatch.json',self.native_report)
        self.native['runtimeDispatchReportSha256']=self.m.sha((self.bundle/'native-report.json').read_bytes())
        self.native['runtimeManifestSha256']=self.m.sha((self.state/'runtime.json').read_bytes())
        self.write(self.bundle/'native-wrapper.json',self.native)
        # The actual native harness writes postSourceIdentity; the state bundle
        # uses canonical postSource, while raw refs retain original byte hashes.
        native_original=dict(self.native)
        if 'postSource'in native_original:native_original['postSourceIdentity']=native_original.pop('postSource')
        native_original_bytes=json.dumps(native_original,sort_keys=True).encode()
        self.wrapper['supplementReportSha256']=self.m.sha((self.bundle/'scene.json').read_bytes())
        self.wrapper['nativePrerequisite']={'receiptSha256':self.m.sha(native_original_bytes),
            'reportSha256':self.native['runtimeDispatchReportSha256'],'runtimeManifestSha256':self.native['runtimeManifestSha256'],
            'logSha256':self.m.sha(self.stdout),'lifecycleResultSha256':self.m.sha((self.state/'result.json').read_bytes())}
        self.write(self.bundle/'wrapper.json',self.wrapper)
        self.originals={name:(self.bundle/name).read_bytes()for name in self.m.SCHEMAS}
        self.originals['native-wrapper.json']=native_original_bytes
        refs={name:{'sha256':self.m.sha(data),'bytes':len(data)}for name,data in self.originals.items()}
        for name in ('native-stdout','supplement-stdout'):refs[name]={'sha256':self.m.sha(self.stdout),'bytes':len(self.stdout)}
        data=(self.state/'result.json').read_bytes();refs['lifecycle-result']={'sha256':self.m.sha(data),'bytes':len(data)}
        self.write(self.bundle/'provenance.json',{'schemaVersion':2,'producerSha256':'a'*64,
            'trust':'reviewed-producer-owned-evidence-not-signature','rawSourceRefs':refs,'tools':self.tools,
            'scope':{'scene':'two-step','source':self.identity,'runId':self.state.name},
            'package':{'name':'@alicloud/autowonder','version':'0.3.43','metadata':{'sha256':self.m.sha(self.metadata),'bytes':len(self.metadata)},
                       'binary':{'sha256':self.m.sha(self.binary),'bytes':len(self.binary)}}})
        self.proof={'schemaVersion':1,'scene':'two-step','proofId':self.bundle.name,'runId':self.state.name,
            'consumerSha256':self.m.sha(Path(self.m.__file__).read_bytes()),'files':{},'logs':{}}
        for name in self.m.FILES:
            data=(self.bundle/name).read_bytes();self.proof['files'][name]={'bytes':len(data),'sha256':self.m.sha(data)}
        for label,path in [('stdout',self.app),('file',self.file)]:
            data=path.read_bytes();lines=data.splitlines(keepends=True);span=b''.join(lines[1:3]);prefix=b''.join(lines[:3])
            self.proof['logs'][label]={'start':1,'end':3,'prefixBytes':len(prefix),'prefixSha256':self.m.sha(prefix),
                'spanBytes':len(span),'spanSha256':self.m.sha(span),'eofLine':2,'eofSha256':self.m.sha(lines[1]),
                'deleteLine':3,'deleteSha256':self.m.sha(lines[2])}
        self.write(self.bundle/'proof.json',self.proof)

    def validate(self):return self.m.validate(self.state,self.app,self.file)

    def test_consumer_is_available(self):
        path=Path(__file__).parents[1]/'lib/supplement_cleanup_evidence.py'
        spec=importlib.util.spec_from_file_location('cleanup_proof',path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        self.assertTrue(callable(module.validate))

    def test_valid_exact_proof_and_append(self):
        self.assertEqual(2,len(self.validate()))
        with self.app.open('ab')as stream:stream.write(b'later append\n')
        self.assertEqual(2,len(self.validate()))

    def test_missing_native_actual_model_and_postsource_refused(self):
        self.native.pop('modelExecution',None);self.native.pop('postSource',None);self.refresh()
        with self.assertRaises(ValueError):self.validate()

    def test_supplied_projection_unknown_nested_private_field_refused(self):
        value=self.m.parse((self.bundle/'scene.json').read_bytes())
        value['cleanupOrder']['customCredential']='SYNTHETIC_SECRET_SENTINEL'
        self.write(self.bundle/'scene.json',value)
        data=(self.bundle/'scene.json').read_bytes();self.proof['files']['scene.json']={'bytes':len(data),'sha256':self.m.sha(data)}
        self.write(self.bundle/'proof.json',self.proof)
        with self.assertRaises(ValueError):self.validate()

    def test_dirfd_enumeration_stops_before_tenth_entry_and_nofollows(self):
        from types import SimpleNamespace
        observed=[];stats=[]
        class Stream:
            def __enter__(self):return self
            def __exit__(self,*unused):return False
            def __iter__(self):
                for number in range(100000):
                    observed.append(number)
                    def status(*,follow_symlinks,n=number):
                        stats.append((n,follow_symlinks));return SimpleNamespace(st_mode=0o40700)
                    yield SimpleNamespace(name=str(number),stat=status)
        with patch.object(self.m.os,'scandir',return_value=Stream()),self.assertRaises(ValueError):
            self.m.entries(self.root,8,True)
        self.assertEqual(9,len(observed));self.assertEqual([(n,False)for n in range(8)],stats)

    def test_symlink_bundle_is_rejected_before_scanning_target(self):
        import shutil
        shutil.rmtree(self.bundle);outside=self.state/'outside';outside.mkdir();self.bundle.symlink_to(outside,target_is_directory=True)
        original=self.m.os.scandir;seen=[]
        def scan(fd):
            seen.append(fd);return original(fd)
        with patch.object(self.m.os,'scandir',side_effect=scan),self.assertRaises(ValueError):self.validate()
        self.assertEqual(1,len(seen))

    def test_prefix_mutation_before_span_refused(self):
        data=self.app.read_bytes();self.app.write_bytes(data.replace(b'prefix',b'Prefix'))
        with self.assertRaises(ValueError):self.validate()

    def test_truncation_and_rotation_refused(self):
        self.app.write_bytes(self.prefix+self.eof)
        with self.assertRaises(ValueError):self.validate()

    def test_failed_scene_native_restoration_or_unknown_chief_refused(self):
        for target,key,value in [(self.scene,'verdict','FAIL'),(self.scene,'processCleanup',False),
            (self.wrapper,'actualExitCode',1),(self.wrapper,'protectedInputsUnchanged',False),
            (self.native,'passed',False),(self.native,'interruptedOrTimedOut',True),
            (self.scene,'checks',list(self.m.CHECKS)[:-1])]:
            old=target[key];target[key]=value;self.refresh()
            with self.subTest(key=key),self.assertRaises(ValueError):self.validate()
            target[key]=old
        self.refresh();self.proof['scene']='chief-official-config-refresh';self.write(self.bundle/'proof.json',self.proof)
        with self.assertRaises(ValueError):self.validate()

    def test_stop_order_and_delete_completion_refused(self):
        for key in ('processStoppedBeforeDelete','deleteCallCompleted'):
            self.scene['cleanupOrder'][key]=False;self.refresh()
            with self.assertRaises(ValueError):self.validate()
            self.scene['cleanupOrder'][key]=True

    def test_unknown_warn_error_foreign_executor_and_nonempty_rid_refused(self):
        variants=[self.eof.replace(b'WARN',b'ERROR'),self.eof.replace(b'executorId=45',b'executorId=-1'),
            self.eof.replace(b'EOFException',b'OtherException'),self.eof.replace(b'WARN|||',b'WARN|unknown||'),
            self.eof.replace(b'ExecutorWsEndpoint',b'UnknownLogger'),self.eof+self.eof]
        for data in variants:
            self.app.write_bytes(self.prefix+data+self.delete)
            with self.subTest(data=data[:30]),self.assertRaises(ValueError):self.validate()
        self.app.write_bytes(self.prefix+self.eof+self.delete)

    def test_server_delete_200_is_real_not_assumed(self):
        self.app.write_bytes(self.prefix+self.eof+self.delete.replace(b'status=200',b'status=401'))
        self.refresh()
        with self.assertRaises(ValueError):self.validate()

    def test_identity_and_source_mismatch_refused(self):
        for key,value in [('tree','c'*40),('dirty',True)]:
            old=self.wrapper['postSource'];self.wrapper['postSource']=dict(self.identity,**{key:value});self.refresh()
            with self.assertRaises(ValueError):self.validate()
            self.wrapper['postSource']=old

    def test_symlink_hardlink_mode_duplicate_proof_refused(self):
        p=self.bundle/'scene.json';backup=p.read_bytes();p.unlink();p.symlink_to(self.state/'runtime.json')
        with self.assertRaises(ValueError):self.validate()
        p.unlink();p.write_bytes(backup);p.chmod(0o644)
        with self.assertRaises(ValueError):self.validate()
        p.chmod(0o600);os.link(p,self.state/'hardlink')
        with self.assertRaises(ValueError):self.validate()

    def test_duplicate_json_key_and_bool_counter_refused(self):
        p=self.bundle/'proof.json';data=p.read_bytes();p.write_bytes(data[:-1]+b',"schemaVersion":1}')
        with self.assertRaises(ValueError):self.validate()
        p.write_bytes(data);self.proof['logs']['stdout']['end']=True;self.write(p,self.proof)
        with self.assertRaises(ValueError):self.validate()

    def test_absent_directory_preserves_original_native_scan(self):
        import shutil
        shutil.rmtree(self.root)
        self.assertEqual([],self.validate())

    def test_empty_optional_directory_needs_no_new_context(self):
        import shutil
        shutil.rmtree(self.bundle);(self.state/'runtime.json').unlink()
        self.assertEqual([],self.validate())

    def test_only_exact_samefile_var_alias_canonicalized(self):
        with patch.object(self.m.Path,'exists',return_value=True),patch.object(self.m.Path,'samefile',return_value=True),patch.object(self.m,'safe',side_effect=lambda p,**kw:p):
            self.assertEqual(Path('/private/var/folders/owned'),self.m.entry('/var/folders/owned'))
            self.assertEqual(Path('/tmp/alias'),self.m.entry('/tmp/alias'))
        with patch.object(self.m.Path,'exists',return_value=True),patch.object(self.m.Path,'samefile',return_value=False),patch.object(self.m,'safe',side_effect=lambda p,**kw:p):
            self.assertEqual(Path('/var/folders/foreign'),self.m.entry('/var/folders/foreign'))

    def test_conflicting_bundle_and_different_log_counterpart_refused(self):
        import shutil
        extra=self.root/'two-step-two';shutil.copytree(self.bundle,extra);extra.chmod(0o700)
        with self.assertRaises(ValueError):self.validate()
        shutil.rmtree(extra)
        self.file.write_bytes(self.prefix+self.eof.replace(b'54,603',b'54,604')+self.delete)
        self.refresh()
        with self.assertRaises(ValueError):self.validate()

    @unittest.skipUnless(Path('/fixed-project/e2e-tests/runtime_dispatch.py').is_file(),'fixed Linux helper fixture only')
    def test_original_owned_runtime_real_child_exits_before_delete(self):
        import sys
        path=Path('/fixed-project/e2e-tests/runtime_dispatch.py')
        spec=importlib.util.spec_from_file_location('unchanged_native',path)
        helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
        order=[];owned=helper.OwnedRuntime([sys.executable,'-c','import time;time.sleep(60)'],{},self.state)
        with owned as process:
            pid=process.pid;order.append('assertions-complete')
            self.assertIsNone(process.poll())
        self.assertTrue(owned.cleaned);self.assertIsNotNone(process.poll())
        self.assertFalse(Path('/proc',str(pid)).exists())
        order.append('original-stop-complete')
        # Fake DELETE is test-only; real producer still requires actual server log.
        order.append('synthetic-delete-call')
        self.assertEqual(['assertions-complete','original-stop-complete','synthetic-delete-call'],order)

    def test_scanner_attributes_only_exact_eof_and_preserves_unknown_failure(self):
        scanner=Path(__file__).parents[1]/'logscan.sh'
        env=dict(os.environ,AW_E2E_STATE_DIR=str(self.state),AW_E2E_APP_LOG=str(self.app),AW_E2E_FILE_LOG=str(self.file))
        result=subprocess.run([str(scanner)],env=env,capture_output=True,text=True,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr)
        with self.app.open('ab')as stream:stream.write(b'2026-11-10 18:00:00,000|WARN|||Other|thread|unknown\n')
        result=subprocess.run([str(scanner)],env=env,capture_output=True,text=True,timeout=10)
        self.assertNotEqual(0,result.returncode)

def chief_contract_fixture(module):
    source = {'commit': 'e1bcfbd068d1191de5283f3d019e7afd85f29974', 'tree': '82c24c6b6ab9faf52a30a738b80c90f90342f730', 'dirty': False}
    ids = dict(workspaceId=1, userId=2, agentId=3, agentVersionId=4, executorId=5, conversationId=6)
    old = {'fingerprint': 'a' * 64, 'officialIds': ['official:old']}
    current = {'fingerprint': 'b' * 64, 'officialIds': ['official:old', 'official:optimize-squad-workflow']}
    expected = dict(source=source, oldIdentity=old, currentIdentity=current, appCid='c' * 64, appImage='sha256:' + 'd' * 64, originalJarSha256='e' * 64, dbIdentity={'containerId': 'f' * 64, 'imageId': 'sha256:' + '1' * 64, 'volumes': []}, composeProject='owned-test', invocationSha256='2' * 64, fixtureJarSha256='6' * 64, redisContainerId='7' * 64, redisNetworkId='8' * 64, maintenanceSourceHashes={k: '9' * 64 for k in ('ttlSourceSha256', 'redisConfigSourceSha256', 'keySourceSha256', 'stringKeySourceSha256')}, nativeProof={k: '3' * 64 for k in ('receiptSha256', 'reportSha256', 'logSha256', 'runtimeManifestSha256', 'lifecycleResultSha256')})
    inv = {'source': source, 'nativeProof': expected['nativeProof'], 'composeProject': 'owned-test'}
    w = dict(mode='chief-official-config-refresh-one-shot', actualExitCode=0, acceptanceVerdict='PASS', modelStarted=True, modelExecution=True, restorationVerified=True, protectedInputsUnchanged=True, interruptedOrTimedOut=False, ownedContainerResidue=False, source=source, postSource=source)
    log = dict(fromBytes=0, toBytes=10, intervalSha256='5' * 64, auditWriteFailures=0, rawLogsPersisted=False)
    isolation = dict(workspaceId=1, memberCount=1, minUserId=2, maxUserId=2, squadCount=0)
    turns = []
    for i, tool in enumerate(['autowonder.list_workitems', None, 'autowonder.list_workitems', 'autowonder.list_squads', None]):
        turn = 10 + i * 2
        submit = 100 + i * 10
        audit = [submit + 1] if tool else []
        scope = dict(workspaceId=1, actorId=2, agentId=3, agentVersionId=4, executorId=5, conversationId=6, turnId=turn, dispatchAttempt=2)
        ta = dict(auditIds=audit, operations=[tool] if tool else [], scope=scope, resultEvidence='SERVER_CALL_INTERNAL_RETURNED_WITHOUT_EXCEPTION', rawArgumentsOrResultsCaptured=False)
        skills = [{'id': 'official:old', 'name': 'old'}, {'id': '7', 'name': 'optimize-squad-workflow'}]
        if i >= 2:
            skills.append({'id': 'official:optimize-squad-workflow', 'name': 'platform-optimize-squad-workflow'})
        turns.append(dict(turnId=turn, sessionSha256='a' * 64 if i < 2 else 'b' * 64, tool=tool, contextRecalled=i in (1, 2, 4), package=dict(sha256=str(i) * 64, skills=skills, optimizerAlias=None if i < 2 else 'platform-optimize-squad-workflow'), toolAudit=ta, windowAudit=dict(auditIds=[submit] + audit, submitHttpAuditId=submit, toolAudit=ta), auditWindow=[submit - 1, submit + 2], auditLogInterval=log, isolationBefore=isolation, isolationAfter=isolation, completionProof=dict(conversation=dict(id=6, workspaceId=1, ownerUserId=2, agentId=3, agentVersionId=4, executorId=5), inbound=dict(id=turn, direction='IN', status='SUCCESS', submittedUserId=2, dispatchAttempt=2), outbound=dict(id=turn + 1, direction='OUT', status='SUCCESS'))))
    paths = ['/api/platform-agent/setup', '/api/platform/conversations/6/commands/refresh'] * 2
    configs = [dict(path=p, auditIds=[150 + i], auditWindow=[149 + i, 150 + i]) for i, p in enumerate(paths)]

    def phase(items, extra=()):
        return dict(beforeAuditId=90, afterAuditId=160, auditIds=sorted([v for t in items for v in t['windowAudit']['auditIds']] + [v for c in extra for v in c['auditIds']]), noAdditionalWorkspaceOperations=True, rawArgumentsOrResultsCaptured=False)
    d = dict(verdict='PASS', scope='official-definition-data-replay-current-server', ownedIds=ids, oldIdentity=old, currentIdentity=current, oldTurns=turns[:2], newTurns=turns[2:], requiredChecks=list(module.CHIEF_DIALOGUE_CHECKS), configurationHttpAudit=configs, oldPhaseAudit=phase(turns[:2]), currentPhaseAudit=phase(turns[2:], configs), oldAuditLogInterval=log, currentAuditLogInterval=log)
    r = dict(scene='chief.official-config-refresh', verdict='PASS', source=source, restoreStatus='RESTORED', dialogue=d, maintenanceWindow=dict(pttlSamples=[2000, 1000, -2], positiveMarkerObserved=True, naturalExpiryObserved=True, ttlSourceSeconds=90, redisModified=False, scope='GRACE_DELAYED_RECOVERY_NOT_IMMEDIATE_RECONNECT', daemonIdentity=dict(pid=71, ppid=70, pgid=71, sid=71, uid=0, startTicks=99, state='S'), sessionIdSha256='1' * 64, lastActualDisconnectTime='2026-11-11 00:00:00,000', unregisterLineSha256='1' * 64, disconnectLineSha256='1' * 64, ttlSourceSha256='9' * 64, redisConfigSourceSha256='9' * 64, redisContainerId='7' * 64, closedKeySha256='1' * 64, redisBinding=dict(host='redis', port=6379, database=0, effectiveConfigurationValidated=True, databaseSource='Jedis.Protocol.DEFAULT_DATABASE', networkId='8' * 64, redisContainerId='7' * 64, keySourceSha256='9' * 64, stringKeySourceSha256='9' * 64)), cleanupOrder=dict(processStoppedBeforeDelete=True, deleteCallCompleted=True, deleteEvidenceSource='server-log-only-not-client-status', processIdentity=dict(pid=71, startTicks=99, pgid=71, sid=71, uid=0)), cleanupProcessProof=dict(originalRuntimeStopCompleted=True, processLeaderExited=True, processGroupEmpty=True), cleanupLogStart={'stdout': 10, 'file': 20}, cleanupLogEnd={'stdout': 15, 'file': 25}, checks=list(module.CHIEF_ALL_CHECKS) if hasattr(module, 'CHIEF_ALL_CHECKS') else [])
    for k in ('attemptStarted', 'runtimeStarted', 'modelStarted', 'modelExecution', 'releaseMutationAttempted', 'releaseMutationObserved', 'fullRestoreVerified', 'processCleanup', 'protectedInputsUnchanged'):
        r[k] = True
    cp = dict(restoreStatus='RESTORED', fullBytesVerified=True, loadedCurrentIdentityVerified=True, sourceCommit=source['commit'], sourceTree=source['tree'], invocationSha256=expected['invocationSha256'], composeProject='owned-test', appContainerId=expected['appCid'], appImageId=expected['appImage'], originalJarSha256=expected['originalJarSha256'], releaseMutationAttempted=True, releaseJournalCreated=True, runtimeStarted=True, modelStarted=True)
    common = dict(runId='owned-test', containerId=expected['appCid'], imageId=expected['appImage'], composeProject='owned-test', sourceCommit=source['commit'], sourceTree=source['tree'], dbIdentity=expected['dbIdentity'], originalSha=expected['originalJarSha256'], fixtureSha=expected['fixtureJarSha256'])
    journal = [dict(common, phase='bound'), dict(runId='owned-test', phase='captured', metadata=dict(uid=0, gid=0, mode=420), fullPreimageSha=expected['originalJarSha256']), dict(runId='owned-test', phase='restarted', goal='legacy', exitCode=0), dict(runId='owned-test', phase='loaded', goal='legacy', loadedIdentityVerified=True, startedAt='old', jarSha=expected['fixtureJarSha256']), dict(runId='owned-test', phase='restarted', goal='current', exitCode=0), dict(runId='owned-test', phase='loaded', goal='current', jarSha=expected['originalJarSha256'], loadedIdentityVerified=True, startedAt='new')]
    return [r, w, inv, cp, journal, expected]

if __name__=='__main__':unittest.main()
