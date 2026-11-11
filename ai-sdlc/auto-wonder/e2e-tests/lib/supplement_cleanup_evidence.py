"""Strict successful two-step/Chief cleanup evidence, never HTTP RID evidence.

Trust is the reviewed producer and owned lifecycle evidence, not a signature.
Only versioned safe projections are admitted; raw originals stay outside state.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import stat

MASTER='e7b4c01c2cf3e06df28d75bc0a9a0bfc909f71d2'
IMAGE='sha256:78350908426f0d76927361c07199f01286c9c5964a28c51a83dad84b3ee61fd8'
CHECKS=frozenset(('real_qoder_online','dispatch_routing_acked_running_succeeded',
 'two_required_steps_completed_in_order','handoff_only_after_step2',
 'final_two_artifact_preview_and_signed_download_exact','rebind_preserves_original_progress',
 'stage_history_identity_and_samples','stage_duration_total_reconciles',
 'rebind_preserves_artifact_ids_and_bytes'))
NATIVE=frozenset(('executor_online','routing_matches','server_acked','server_running',
 'dispatch_succeeded','downloaded_artifact_matches'))
LINE=re.compile(rb'^(?P<ts>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d[,.]\d+)\|(?P<level>[A-Z]+)\|(?P<rid>[^|]*)\|(?P<endpoint>[^|]*)\|(?P<logger>[^|]*)\|(?P<thread>[^|]*)\|(?P<msg>[^\r\n]*)\n$')
SHA=re.compile(r'[0-9a-f]{64}')
FILES=('wrapper.json','scene.json','native-wrapper.json','native-report.json','context.json','provenance.json')
TOOL_FILES=('two-step-runtime-launch.py','two-step-runtime-container.py','two-step-runtime-probe.py',
            'two-step-assertions.py','acceptance_probe_guards.py','response-observer.py','owned-daemon-cleanup-fence.py')
TOOL_NAMES=('harness/run.py','harness/entrypoint.py',*TOOL_FILES)
CHIEF_TOOL_FILES=('chief-release-launch.py','chief-release-container.py','chief-release-run.py',
 'chief-release-actuator.py','chief-release-conversation.py','chief-release-prepare.py','chief-release-supervisor.py',
 'chief-runtime-maintenance.py','owned-daemon-cleanup-fence.py','two-step-runtime-launch.py','acceptance_probe_guards.py')
IDENTITY={'commit':str,'tree':str,'dirty':bool}
REF={'sha256':str,'bytes':int}
PROCESS={'pid':int,'startTicks':int,'pgid':int,'sid':int,'uid':int}
SOURCE_PROOF={'receiptSha256':str,'reportSha256':str,'logSha256':str,'runtimeManifestSha256':str,'lifecycleResultSha256':str}
BASE={'source':IDENTITY,'postSource':IDENTITY,'stateDir':str,'image':str,'runtimeVersion':str,
      'actualExitCode':int,'passed':bool,'interruptedOrTimedOut':bool,'ownedContainerResidue':bool,'modelExecution':bool,
      'metadataSha256':str,'nativeBinarySha256':str,'logSha256':str}
SCHEMAS={
 'wrapper.json':dict(BASE,mode=str,protectedInputsUnchanged=bool,nativePrerequisiteValidated=bool,
    sourceScenarioCommit=str,nativePrerequisite=SOURCE_PROOF,acceptanceVerdict=str,supplementVerdict=str,
    supplementReportSha256=str,nativeHarnessSha256=str,relaySha256=str,
    supplementFileSha256={key:str for key in TOOL_FILES}),
 'native-wrapper.json':dict(BASE,mode=str,lifecycleVerdict=str,runtimeDispatchReportSha256=str,
    runtimeManifestSha256=str,harnessSha256=str,entrypointSha256=str),
 'native-report.json':dict(verdict=str,cleanup=bool,checks=[str],runtimeVersion=str,sourceRuntimeVersion=str,
    artifactSha256=str,**{key:int for key in ('executorId','workspaceId','workitemId','dispatchId','artifactId','agentId','agentVersionId','stepId')}),
 'scene.json':dict(scene=str,sourceCommit=str,sourceScenarioCommit=str,verdict=str,phase=str,runtimeVersion=str,
    processCleanup=bool,realModel=bool,ownedExecutorDeletedAfterAssertions=bool,checks=[str],
    executorId=int,workspaceId=int,workitemId=int,dispatchId=int,
    cleanupLogStart={'stdout':int,'file':int},cleanupLogEnd={'stdout':int,'file':int},
    cleanupOrder={'processStoppedBeforeDelete':bool,'deleteCallCompleted':bool,'deleteEvidenceSource':str,'processIdentity':PROCESS},
    cleanupProcessProof={'originalRuntimeStopCompleted':bool,'processLeaderExited':bool,'processGroupEmpty':bool}),
 'context.json':{'runId':str,'git':{'commit':str,'dirty':bool},'logs':{'springBootConsole':str,'springBootFile':str}}}
CHIEF_WRAPPER_SCHEMA={key:BASE[key]for key in ('source','postSource','stateDir','image','actualExitCode',
 'interruptedOrTimedOut','ownedContainerResidue','modelExecution','logSha256')}
CHIEF_WRAPPER_SCHEMA.update(mode=str,acceptanceVerdict=str,restorationVerified=bool,protectedInputsUnchanged=bool,
 modelStarted=bool,nativePrerequisiteValidated=bool,reportSha256=str,fileSha256={name:str for name in CHIEF_TOOL_FILES})
RECHECK_KEYS=('sourceRechecked','stackIdentityRechecked','protectedFilesUnchanged','fixtureFilesUnchanged','jarShaRechecked')

def project(value,schema,strict=False):
    """Versioned allowlist; arbitrary original fields are never serialized."""
    if type(schema)is dict:
        need(type(value)is dict and set(schema).issubset(value)and (not strict or set(value)==set(schema)))
        return {key:project(value[key],kind,strict)for key,kind in schema.items()}
    if type(schema)is list:
        need(type(value)is list and len(value)<=128)
        return [project(item,schema[0],strict)for item in value]
    need(type(value)is schema)
    if schema is str:need(len(value)<=4096 and '\x00'not in value and '\n'not in value and '\r'not in value)
    return value

def project_context(value):return project(value,SCHEMAS['context.json'])

def provenance_ref_check(item,*,scene,group,role):
    project(item,REF,strict=True)
    empty=(scene=='chief-official-config-refresh'and group=='raw'and role=='supplement-stdout'
           and item['bytes']==0 and item['sha256']==hashlib.sha256(b'').hexdigest())
    need(SHA.fullmatch(item['sha256'])and (0<item['bytes']<=134217728 or empty))

def provenance_check(values,current_context_bytes):
    p=values['provenance.json']
    scene=p.get('scope',{}).get('scene');need(scene in ('two-step','chief-official-config-refresh'))
    chief=scene!='two-step'
    tool_files=CHIEF_TOOL_FILES if chief else TOOL_FILES
    tool_names=('harness/run.py','harness/entrypoint.py',*tool_files)
    extra_refs=('chief-invocation','chief-checkpoint','chief-release-journal','chief-fixture-plan')if chief else ()
    schema={'schemaVersion':int,'producerSha256':str,'trust':str,'scope':{'scene':str,'source':IDENTITY,'runId':str},
            'rawSourceRefs':{key:REF for key in (*SCHEMAS,'native-stdout','supplement-stdout','lifecycle-result',*extra_refs)},
            'tools':{key:REF for key in tool_names},'package':{'name':str,'version':str,'metadata':REF,'binary':REF}}
    expected=None;inspection=None
    if chief:
        schema['producerTools']={'chief-context-builder':REF}
        schema['recheckClosure']={key:bool for key in RECHECK_KEYS}
        need(type(p.get('recheckClosure'))is dict and all(p['recheckClosure'].get(key)is True for key in RECHECK_KEYS))
        expected=validate_chief_expected_context(p['expectedContext'])
        inspection=p['inspectionSourceRefs'];need(type(inspection)is list and len(inspection)==len(context_REF_NAMES))
        seen=set()
        for item in inspection:
            project(item,{'name':str,'sha256':str,'bytes':int},strict=True)
            need(item['name']in context_REF_NAMES and item['name']not in seen
                 and SHA.fullmatch(item['sha256'])and 0<=item['bytes']<=536870912);seen.add(item['name'])
        need(seen==context_REF_NAMES)
        p={key:value for key,value in p.items()if key not in ('expectedContext','inspectionSourceRefs')}
    project(p,schema,strict=True)
    need(p['schemaVersion']==2 and SHA.fullmatch(p['producerSha256'])
         and p['trust']=='reviewed-producer-owned-evidence-not-signature')
    for group,refs in (('raw',p['rawSourceRefs']),('tools',p['tools']),
                       ('package',{'metadata':p['package']['metadata'],'binary':p['package']['binary']})):
        for role,item in refs.items():provenance_ref_check(item,scene=scene,group=group,role=role)
    for name,schema in SCHEMAS.items():
        if chief and name in ('wrapper.json','scene.json'):continue
        project(values[name],schema,strict=True)
    if chief:project(values['wrapper.json'],CHIEF_WRAPPER_SCHEMA,strict=True)
    w,n=values['wrapper.json'],values['native-wrapper.json'];refs=p['rawSourceRefs'];tools=p['tools'];package=p['package']
    need(refs['context.json']['sha256']==sha(current_context_bytes)and refs['context.json']['bytes']==len(current_context_bytes)
         and w['reportSha256'if chief else'supplementReportSha256']==refs['scene.json']['sha256']
         and n['runtimeDispatchReportSha256']==refs['native-report.json']['sha256']
         and w['logSha256']==refs['supplement-stdout']['sha256']
         and n['logSha256']==refs['native-stdout']['sha256'])
    prerequisite=expected['nativeProof']if chief else w['nativePrerequisite']
    need(prerequisite['receiptSha256']==refs['native-wrapper.json']['sha256']
         and prerequisite['reportSha256']==refs['native-report.json']['sha256']
         and prerequisite['logSha256']==refs['native-stdout']['sha256']
         and prerequisite['lifecycleResultSha256']==refs['lifecycle-result']['sha256']
         and p['scope']=={'scene':scene,'source':w['source'],'runId':values['context.json']['runId']})
    need(n['harnessSha256']==tools['harness/run.py']['sha256']
         and n['entrypointSha256']==tools['harness/entrypoint.py']['sha256']
         and package['name']=='@alicloud/autowonder'and package['version']=='0.3.43'
         and package['metadata']['sha256']==n['metadataSha256']
         and package['binary']['sha256']==n['nativeBinarySha256'])
    if chief:
        need(w['fileSha256']=={key:tools[key]['sha256']for key in tool_files}
             and expected['invocationSha256']==refs['chief-invocation']['sha256']and expected['source']==w['source'])
        item=p['producerTools']['chief-context-builder'];need(SHA.fullmatch(item['sha256'])and 0<item['bytes']<=2097152)
        measured={item['name']:item for item in inspection}
        for name,tool in [('official-views-helper','chief-release-conversation.py'),
                          ('redis-binding-helper','chief-release-run.py'),('launch-constants','chief-release-launch.py')]:
            need(measured[name]['sha256']==tools[tool]['sha256'])
        for name in ('context.json','native-report.json'):
            key='runtime.json'if name=='context.json'else'runtime-dispatch.json'
            need(measured[key]['sha256']==refs[name]['sha256']and measured[key]['bytes']==refs[name]['bytes'])
        need(measured['fixture-plan']['sha256']==refs['chief-fixture-plan']['sha256']
             and measured['result.json']['sha256']==refs['lifecycle-result']['sha256']
             and measured['fixture-restore-exact.jar']['sha256']==expected['originalJarSha256']
             and measured['fixture-legacy-definition-fixture.jar']['sha256']==expected['fixtureJarSha256'])
        for key,digest in expected['maintenanceSourceHashes'].items():need(measured['source-'+key]['sha256']==digest)
        p.update(expectedContext=expected,inspectionSourceRefs=inspection)
    else:
        need(w['nativeHarnessSha256']==n['harnessSha256']and w['relaySha256']==n['entrypointSha256']
             and w['supplementFileSha256']=={key:tools[key]['sha256']for key in TOOL_FILES}
             and w['metadataSha256']==n['metadataSha256']and w['nativeBinarySha256']==n['nativeBinarySha256'])
    return p

def need(value):
    if not value:raise ValueError('SUPPLEMENT_CLEANUP_EVIDENCE_REFUSED')

def safe(path,directory=False,mode=None):
    path=Path(os.path.abspath(path))
    for p in (path,*path.parents):need(not p.is_symlink())
    info=path.stat()
    need(stat.S_ISDIR(info.st_mode)if directory else stat.S_ISREG(info.st_mode)and info.st_nlink==1)
    if mode is not None:need(stat.S_IMODE(info.st_mode)==mode)
    return path

def raw(path,mode=0o600,limit=2097152):
    path=safe(path,mode=mode)
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb')as stream:
        before=os.fstat(stream.fileno());need(before.st_size<=limit and stat.S_ISREG(before.st_mode)and before.st_nlink==1
             and (mode is None or stat.S_IMODE(before.st_mode)==mode))
        data=stream.read(limit+1);after=os.fstat(stream.fileno())
        need(len(data)<=limit and (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns))
        return data

def sha(data):return hashlib.sha256(data).hexdigest()

def parse(data):
    def unique(pairs):
        value={}
        for key,item in pairs:need(key not in value);value[key]=item
        return value
    value=json.loads(data,object_pairs_hook=unique);need(type(value)is dict);return value

def entries(directory,limit,directories):
    """Bound enumeration before traversal; inspect every admitted inode nofollow."""
    safe(directory,directory=True,mode=0o700)
    fd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    result=[]
    try:
        with os.scandir(fd)as stream:
            for item in stream:
                need(len(result)<limit)
                info=item.stat(follow_symlinks=False)
                need(stat.S_ISDIR(info.st_mode)if directories else stat.S_ISREG(info.st_mode)and info.st_nlink==1)
                result.append(Path(directory)/item.name)
    finally:os.close(fd)
    return sorted(result)

def pos(value):need(type(value)is int and 0<value<2147483648);return value

def entry(value):
    """Only measured macOS /var alias (or the same dual Linux bind) is allowed."""
    path=Path(os.path.abspath(value))
    if str(path).startswith('/var/'):
        canonical=Path('/private'+str(path))
        if canonical.exists()and path.samefile(canonical):path=canonical
    return safe(path,directory=path.is_dir())

def checks(value,expected):
    need(type(value)is list and all(type(x)is str for x in value)and len(value)==len(set(value))and set(value)==expected)

def admit_scene(values,context_bytes,state,scene):
    w,payload,n,nr=(values[key]for key in FILES[:4]);p=values['provenance.json'];refs=p['rawSourceRefs']
    identity=w['source'];context=parse(context_bytes)
    need(type(identity)is dict and set(identity)==set(IDENTITY)and identity['commit']==context['git']['commit']
         and identity['dirty']is False and re.fullmatch('[0-9a-f]{40}',identity['tree'])
         and n['source']==n['postSource']==w['postSource']==identity)
    for item in (w,n):
        need(type(item['actualExitCode'])is int and item['actualExitCode']==0 and item['interruptedOrTimedOut']is False
             and item['ownedContainerResidue']is False and item['image']==IMAGE and entry(item['stateDir'])==state)
    need(n['mode']=='original-verify-check-with-runtime'and n['passed']is True and n['modelExecution']is True
         and n['lifecycleVerdict']=='PASS'and n['runtimeVersion']=='0.3.43'
         and n['runtimeManifestSha256']==sha(context_bytes)
         and nr['runtimeVersion']==nr['sourceRuntimeVersion']=='0.3.43'
         and nr['verdict']=='PASS'and nr['cleanup']is True and SHA.fullmatch(nr['artifactSha256'])
         and sha(raw(state/'runtime-dispatch.json'))==refs['native-report.json']['sha256'])
    checks(nr['checks'],NATIVE)
    for key in ('executorId','workspaceId','workitemId','dispatchId','artifactId','agentId','agentVersionId','stepId'):pos(nr[key])
    if scene=='two-step':
        r=payload;prerequisite=w['nativePrerequisite']
        need(w['mode']=='supplemental-two-step-real-qoder'and w['passed']is True and w['modelExecution']is True
             and w['protectedInputsUnchanged']is True and w['nativePrerequisiteValidated']is True
             and w['sourceScenarioCommit']==MASTER and w['acceptanceVerdict']==w['supplementVerdict']=='PASS'
             and w['runtimeVersion']=='0.3.43'and r['runtimeVersion']=='0.3.43')
        need(r['scene']=='runtime.real-qoder-delivery'and r['verdict']=='PASS'and r['phase']=='complete'
             and r['sourceCommit']==identity['commit']and r['sourceScenarioCommit']==MASTER
             and r['realModel']is True and r['processCleanup']is True and r['ownedExecutorDeletedAfterAssertions']is True)
        checks(r['checks'],CHECKS)
        for key in ('workspaceId','workitemId','dispatchId'):pos(r[key])
        executor=pos(r['executorId'])
    else:
        need(scene=='chief-official-config-refresh')
        # Full typed expected-context admission is defined by the reviewed
        # read-only context builder, never reconstructed from the report.
        expected=validate_chief_expected_context(p['expectedContext'])
        facts=chief_validate_chief_projection(payload,expected)
        need(payload['wrapper']=={key:w[key]for key in payload['wrapper']})
        r=payload['report'];executor=pos(facts['executorId']);pos(facts['workspaceId'])
        prerequisite=expected['nativeProof']
    need(prerequisite['receiptSha256']==refs['native-wrapper.json']['sha256']
         and prerequisite['reportSha256']==refs['native-report.json']['sha256']
         and prerequisite['runtimeManifestSha256']==sha(context_bytes)
         and prerequisite['logSha256']==refs['native-stdout']['sha256']
         and prerequisite['lifecycleResultSha256']==refs['lifecycle-result']['sha256'])
    process=r['cleanupProcessProof']
    need(set(process)=={'originalRuntimeStopCompleted','processLeaderExited','processGroupEmpty'}and all(v is True for v in process.values())
         and executor!=nr['executorId'])
    return r,executor

def validate(state,app_log,file_log,_prepared_root=None):
    state=Path(os.path.abspath(state))
    # The scanner never supplies _prepared_root. Producer uses it solely for
    # validation of its exclusive outside-state staging tree before publication.
    root=state/'supplement-cleanup-evidence'if _prepared_root is None else Path(_prepared_root)
    if not root.exists()and not root.is_symlink():return []
    state=entry(state)
    root=state/'supplement-cleanup-evidence'if _prepared_root is None else Path(_prepared_root)
    safe(root,directory=True,mode=0o700)
    dirs=entries(root,8,True)
    if not dirs:return []
    context_bytes=raw(state/'runtime.json',mode=None);context=parse(context_bytes)
    need(context.get('runId')==state.name)
    context_git=context.get('git',{});need(re.fullmatch('[0-9a-f]{40}',context_git.get('commit',''))and context_git.get('dirty')is False)
    logs={}
    for label,path,key in [('stdout',app_log,'springBootConsole'),('file',file_log,'springBootFile')]:
        p=entry(path);need(p.is_relative_to(state)and p==entry(context['logs'][key]))
        logs[label]=raw(p,mode=None,limit=33554432).splitlines(keepends=True)
    need(Path(app_log).resolve()!=Path(file_log).resolve())
    claims=[];executors=set();identities=set();total_bytes=0
    for directory in dirs:
        safe(directory,directory=True,mode=0o700)
        need(re.fullmatch('[a-z0-9][a-z0-9-]{0,63}',directory.name))
        need({p.name for p in entries(directory,len(FILES)+1,False)}==set(FILES)|{'proof.json'})
        proof=parse(raw(directory/'proof.json'))
        need(set(proof)=={'schemaVersion','scene','proofId','runId','consumerSha256','files','logs'})
        need(proof['schemaVersion']==1 and type(proof['schemaVersion'])is int
             and proof['scene']in ('two-step','chief-official-config-refresh')and proof['proofId']==directory.name
             and proof['runId']==state.name and proof['consumerSha256']==sha(Path(__file__).read_bytes()))
        need(set(proof['files'])==set(FILES))
        values={}
        for name in FILES:
            data=raw(directory/name);file_entry=proof['files'][name]
            total_bytes+=len(data);need(total_bytes<=8388608)
            need(set(file_entry)=={'sha256','bytes'}and type(file_entry['bytes'])is int and file_entry['bytes']==len(data)and file_entry['sha256']==sha(data))
            values[name]=parse(data)
        need(values['context.json']==project_context(context))
        provenance=provenance_check(values,context_bytes)
        need(proof['scene']==provenance['scope']['scene'])
        refs=provenance['rawSourceRefs']
        report,executor=admit_scene(values,context_bytes,state,proof['scene'])
        need(executor not in executors);executors.add(executor)
        order=report.get('cleanupOrder');need(type(order)is dict and set(order)=={'processStoppedBeforeDelete','deleteCallCompleted','processIdentity','deleteEvidenceSource'})
        need(order['processStoppedBeforeDelete']is True and order['deleteCallCompleted']is True
             and order['deleteEvidenceSource']=='server-log-only-not-client-status')
        process=order['processIdentity'];need(type(process)is dict and set(process)=={'pid','startTicks','pgid','sid','uid'})
        for key in ('pid','startTicks','pgid','sid'):pos(process[key])
        need(process['pid']==process['pgid']==process['sid']and type(process['uid'])is int and process['uid']>=0)
        need(set(proof['logs'])=={'stdout','file'})
        events=[]
        for label,lines in logs.items():
            item=proof['logs'][label]
            need(set(item)=={'start','end','prefixBytes','prefixSha256','spanBytes','spanSha256','eofLine','eofSha256','deleteLine','deleteSha256'})
            start,end=item['start'],item['end'];need(type(start)is int and type(end)is int and 0<=start<end<=len(lines))
            need(report['cleanupLogStart'][label]==start and report['cleanupLogEnd'][label]==end)
            prefix=b''.join(lines[:end]);span=b''.join(lines[start:end])
            need(prefix.endswith(b'\n')and type(item['prefixBytes'])is int and item['prefixBytes']==len(prefix)
                 and item['prefixSha256']==sha(prefix)and type(item['spanBytes'])is int and item['spanBytes']==len(span)and item['spanSha256']==sha(span))
            warnings=[]
            for number,line in enumerate(lines[start:end],start+1):
                match=LINE.fullmatch(line)
                if (match and match['level']in (b'WARN',b'ERROR'))or (not match and re.search(rb'\b(WARN|ERROR)\b',line)):
                    need(match is not None and match['level']==b'WARN'and match['logger']==b'ExecutorWsEndpoint'
                         and not match['rid']and not match['endpoint']
                         and match['msg']in (f'WS error executorId={executor} java.io.EOFException'.encode(),f'WS error executorId={executor} java.io.EOFException '.encode()))
                    warnings.append((number,match,line))
            need(len(warnings)==1)
            number,match,line=warnings[0]
            need(type(item['eofLine'])is int and item['eofLine']==number and item['eofSha256']==sha(line))
            delete=pos(item['deleteLine']);need(start<delete<=end and item['deleteSha256']==sha(lines[delete-1]))
            dm=LINE.fullmatch(lines[delete-1]);need(dm is not None and dm['level']==b'INFO'
                and re.fullmatch(rb'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}',dm['rid'])
                and dm['endpoint']==f'DELETE /api/executors/{executor}'.encode()
                and re.fullmatch(rb'Response finished requestPath=/api/executors/'+str(executor).encode()+rb' status=200 elapsedMs=[0-9]+ ?',dm['msg']))
            events.append((match['ts'],match['thread'],match['msg'].rstrip(b' '),dm['rid']))
            key=(label,number);need(key not in identities);identities.add(key)
            claims.append({'source':label,'line':number,'sha256':sha(line),'proofId':proof['proofId'],'scene':proof['scene']})
        need(events[0]==events[1])
    return claims

# Static renamed copy of reviewed Chief adapter; no dynamic exec/import.
"""Pure Chief deep-contract proposal. No I/O, execution, or authorization.

Caller MUST independently bind original file bytes, current source/tools/native
PASS, source-derived official views, state/log paths and server DELETE200 proof.
`expected` is that verified context, never constructed from report claims.
"""
import re
import hashlib
import json
CHIEF_DIALOGUE_CHECKS = ('old_official_identity_exact', 'same_owned_chief_version_conversation_context', 'five_real_qoder_turns_and_server_audits', 'current_official_refresh_idempotent', 'legacy_skill_ids_bindings_custom_preserved', 'optional_optimizer_readonly_empty_stop', 'provider_session_refresh_and_stability', 'phase_audit_log_integrity')
CHIEF_ALL_CHECKS = CHIEF_DIALOGUE_CHECKS + ('current_jar_full_bytes_restored', 'loaded_current_identity_verified', 'same_app_and_database', 'natural_closed_session_lease_expired', 'original_owned_runtime_cleanup', 'owned_executor_public_delete_after_stop')

def chief_need(value):
    if not value:
        raise ValueError('CHIEF_SUCCESS_CONTRACT_REFUSED')

def chief_integer(value, minimum=1):
    return type(value) is int and value >= minimum

def chief_sha(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None

def chief_unique(values):
    chief_need(isinstance(values, list) and all((chief_integer(v) for v in values)) and (len(values) == len(set(values))))
    return set(values)

def chief_exact_checks(actual, expected):
    chief_need(isinstance(actual, list) and len(actual) == len(expected) and (set(actual) == set(expected)))

def chief_log_proof(value):
    chief_need(value.get('auditWriteFailures') == 0 and type(value.get('auditWriteFailures')) is int and chief_integer(value.get('fromBytes'), 0) and chief_integer(value.get('toBytes'), 0) and (value['toBytes'] >= value['fromBytes']) and chief_sha(value.get('intervalSha256')) and (value.get('rawLogsPersisted') is False))

def chief_window(value, ids):
    chief_need(isinstance(value, list) and len(value) == 2 and all((chief_integer(v, 0) for v in value)) and (value[0] <= value[1]) and all((value[0] < v <= value[1] for v in ids)))

def chief_validate_chief_success(report, wrapper, invocation, checkpoint, journal, expected):
    try:
        return chief__validate(report, wrapper, invocation, checkpoint, journal, expected)
    except (KeyError, TypeError, AttributeError, IndexError):
        raise ValueError('CHIEF_SUCCESS_CONTRACT_REFUSED') from None

def chief__validate(r, w, inv, cp, journal, e):
    source = e['source']
    chief_need(source.get('dirty') is False and chief_sha(e['invocationSha256']))
    chief_need(r['source'] == w['source'] == w['postSource'] == inv['source'] == source)
    chief_need(inv['nativeProof'] == e['nativeProof'] and isinstance(e['nativeProof'], dict) and chief_sha(e['nativeProof'].get('receiptSha256')) and chief_sha(e['nativeProof'].get('reportSha256')))
    chief_need(w['mode'] == 'chief-official-config-refresh-one-shot' and type(w['actualExitCode']) is int and (w['actualExitCode'] == 0) and (w['acceptanceVerdict'] == 'PASS'))
    chief_need(all((w.get(k) is True for k in ('modelStarted', 'modelExecution', 'restorationVerified', 'protectedInputsUnchanged'))) and all((w.get(k) is False for k in ('interruptedOrTimedOut', 'ownedContainerResidue'))))
    chief_need(r['scene'] == 'chief.official-config-refresh' and r['verdict'] == 'PASS' and (r['restoreStatus'] == 'RESTORED'))
    chief_need(all((r.get(k) is True for k in ('attemptStarted', 'runtimeStarted', 'modelStarted', 'modelExecution', 'releaseMutationAttempted', 'releaseMutationObserved', 'fullRestoreVerified', 'processCleanup', 'protectedInputsUnchanged'))))
    chief_need(not any((k.endswith(('Error', 'FailureClass')) or k in ('errorCode', 'failureClass') for k in r)))
    chief_exact_checks(r['checks'], CHIEF_ALL_CHECKS)
    d = r['dialogue']
    chief_need(d['verdict'] == 'PASS' and d['scope'] == 'official-definition-data-replay-current-server')
    chief_exact_checks(d['requiredChecks'], CHIEF_DIALOGUE_CHECKS)
    ids = d['ownedIds']
    chief_need(set(ids) == {'workspaceId', 'userId', 'agentId', 'agentVersionId', 'executorId', 'conversationId'} and all((chief_integer(v) for v in ids.values())))
    chief_need(d['oldIdentity'] == e['oldIdentity'] and d['currentIdentity'] == e['currentIdentity'] and chief_sha(d['oldIdentity']['fingerprint']) and chief_sha(d['currentIdentity']['fingerprint']))
    old, new = (d['oldTurns'], d['newTurns'])
    chief_need(len(old) == 2 and len(new) == 3)
    turns = old + new
    chief_need(all((chief_sha(t['sessionSha256']) and 'session' not in t for t in turns)))
    chief_need(old[0]['sessionSha256'] == old[1]['sessionSha256'] and new[0]['sessionSha256'] == new[1]['sessionSha256'] == new[2]['sessionSha256'] and (old[0]['sessionSha256'] != new[0]['sessionSha256']))
    expected_tools = ['autowonder.list_workitems', None, 'autowonder.list_workitems', 'autowonder.list_squads', None]
    all_audit = set()
    turn_ids = []
    per_turn = []
    for i, (t, tool) in enumerate(zip(turns, expected_tools)):
        chief_need(t['tool'] == tool and t['contextRecalled'] is (i in (1, 2, 4)))
        proof = t['completionProof']
        c = proof['conversation']
        incoming = proof['inbound']
        out = proof['outbound']
        chief_need(all((type(v) is int for v in c.values())) and c == dict(id=ids['conversationId'], workspaceId=ids['workspaceId'], ownerUserId=ids['userId'], agentId=ids['agentId'], agentVersionId=ids['agentVersionId'], executorId=ids['executorId']))
        chief_need(chief_integer(t['turnId']) and incoming['id'] == t['turnId'] and (incoming['direction'] == 'IN') and (incoming['status'] == 'SUCCESS') and (type(incoming['submittedUserId']) is int) and (incoming['submittedUserId'] == ids['userId']) and chief_integer(incoming['dispatchAttempt']) and chief_integer(out['id']) and (out['direction'] == 'OUT') and (out['status'] == 'SUCCESS'))
        turn_ids.extend([incoming['id'], out['id']])
        ta = t['toolAudit']
        scope = dict(workspaceId=ids['workspaceId'], actorId=ids['userId'], agentId=ids['agentId'], agentVersionId=ids['agentVersionId'], executorId=ids['executorId'], conversationId=ids['conversationId'], turnId=t['turnId'], dispatchAttempt=incoming['dispatchAttempt'])
        chief_need(ta['scope'] == scope and all((type(v) is int for v in ta['scope'].values())))
        aid = chief_unique(ta['auditIds'])
        ops = ta['operations']
        chief_need(isinstance(ops, list) and len(ops) == len(aid) and all((op in ({tool} if tool else {'autowonder.list_workitems', 'autowonder.list_squads'}) for op in ops)) and (tool is None or tool in ops))
        chief_need(ta['resultEvidence'] == 'SERVER_CALL_INTERNAL_RETURNED_WITHOUT_EXCEPTION' and ta['rawArgumentsOrResultsCaptured'] is False)
        wa = t['windowAudit']
        chief_need(wa['toolAudit'] == ta and chief_integer(wa['submitHttpAuditId']))
        whole = chief_unique(wa['auditIds'])
        chief_need(wa['submitHttpAuditId'] not in aid and whole == aid | {wa['submitHttpAuditId']} and (not whole & all_audit))
        chief_window(t['auditWindow'], whole)
        all_audit |= whole
        per_turn.append(whole)
        chief_log_proof(t['auditLogInterval'])
        for snapshot in (t['isolationBefore'], t['isolationAfter']):
            chief_need(all((type(snapshot.get(k)) is int for k in ('workspaceId', 'memberCount', 'minUserId', 'maxUserId', 'squadCount'))) and snapshot['workspaceId'] == ids['workspaceId'] and (snapshot['memberCount'] == 1) and (snapshot['minUserId'] == snapshot['maxUserId'] == ids['userId']))
            if i == 3:
                chief_need(snapshot['squadCount'] == 0)
        p = t['package']
        chief_need(chief_sha(p['sha256']) and isinstance(p['skills'], list))
        skill_ids = [s['id'] for s in p['skills']]
        chief_need(len(skill_ids) == len(set(skill_ids)))
        required = e['oldIdentity' if i < 2 else 'currentIdentity']['officialIds']
        chief_need(set(required).issubset(skill_ids))
        custom = [s for s in p['skills'] if re.fullmatch('[1-9][0-9]*', str(s['id'])) and s['name'] == 'optimize-squad-workflow']
        chief_need(len(custom) == 1)
        chief_need(set(skill_ids) == set(required) | {custom[0]['id']})
        if i == 0:
            custom_id = custom[0]['id']
        chief_need(custom[0]['id'] == custom_id)
        if i >= 2:
            chief_need(re.fullmatch('platform-optimize-squad-workflow(?:-[2-9][0-9]*)?', p['optimizerAlias']) is not None)
    chief_need(len(turn_ids) == len(set(turn_ids)))
    posts = d['configurationHttpAudit']
    paths = ['/api/platform-agent/setup', f"/api/platform/conversations/{ids['conversationId']}/commands/refresh"] * 2
    chief_need(len(posts) == 4)
    config_ids = set()
    for post, path in zip(posts, paths):
        chief_need(post['path'] == path)
        pid = chief_unique(post['auditIds'])
        chief_need(len(pid) == 1 and (not pid & (all_audit | config_ids)))
        chief_window(post['auditWindow'], pid)
        config_ids |= pid
    for phase, actual in ((d['oldPhaseAudit'], set.union(*per_turn[:2])), (d['currentPhaseAudit'], set.union(*per_turn[2:]) | config_ids)):
        chief_need(chief_unique(phase['auditIds']) == actual and phase['noAdditionalWorkspaceOperations'] is True and (phase['rawArgumentsOrResultsCaptured'] is False))
        chief_window([phase['beforeAuditId'], phase['afterAuditId']], actual)
    chief_log_proof(d['oldAuditLogInterval'])
    chief_log_proof(d['currentAuditLogInterval'])
    chief_need(cp['restoreStatus'] == 'RESTORED' and all((cp.get(k) is True for k in ('fullBytesVerified', 'loadedCurrentIdentityVerified', 'releaseMutationAttempted', 'releaseJournalCreated', 'runtimeStarted', 'modelStarted'))))
    for key, value in dict(sourceCommit=source['commit'], sourceTree=source['tree'], invocationSha256=e['invocationSha256'], composeProject=e['composeProject'], appContainerId=e['appCid'], appImageId=e['appImage'], originalJarSha256=e['originalJarSha256']).items():
        chief_need(cp[key] == value)
    chief_need(inv['composeProject'] == e['composeProject'] and isinstance(journal, list) and (len(journal) >= 3))
    bound = journal[0]
    chief_need(bound['phase'] == 'bound' and all((row['runId'] == bound['runId'] for row in journal)))
    for key, value in dict(containerId=e['appCid'], imageId=e['appImage'], composeProject=e['composeProject'], sourceCommit=source['commit'], sourceTree=source['tree'], dbIdentity=e['dbIdentity'], originalSha=e['originalJarSha256'], fixtureSha=e['fixtureJarSha256']).items():
        chief_need(bound[key] == value)
    loaded = [row for row in journal if row.get('phase') == 'loaded']
    restarted = [row for row in journal if row.get('phase') == 'restarted']
    chief_need(len(restarted) == 2 and [x['goal'] for x in restarted] == ['legacy', 'current'] and all((type(x['exitCode']) is int and x['exitCode'] == 0 for x in restarted)))
    captures = [row for row in journal if row.get('phase') == 'captured']
    chief_need(len(captures) == 1 and captures[0]['fullPreimageSha'] == e['originalJarSha256'] and (captures[0]['metadata'] == dict(uid=0, gid=0, mode=420)) and all((type(v) is int for v in captures[0]['metadata'].values())))
    chief_need(len(loaded) == 2 and [x['goal'] for x in loaded] == ['legacy', 'current'] and all((x['loadedIdentityVerified'] is True and isinstance(x['startedAt'], str) and x['startedAt'] for x in loaded)) and (loaded[0]['startedAt'] != loaded[1]['startedAt']) and (journal[-1] == loaded[-1]) and (loaded[-1]['jarSha'] == e['originalJarSha256']))
    chief_need(loaded[0]['jarSha']==e['fixtureJarSha256'])
    chief_need(journal.index(restarted[0]) < journal.index(loaded[0]) < journal.index(restarted[1]) < journal.index(loaded[1]))
    lease = r['maintenanceWindow']
    samples = lease['pttlSamples']
    chief_need(isinstance(samples, list) and 2 <= len(samples) <= 122 and chief_integer(samples[0]) and (samples[0] <= 90000) and (type(samples[-1]) is int) and (samples[-1] == -2) and all((chief_integer(v, 0) and v <= 90000 for v in samples[:-1])) and all((b <= a for a, b in zip(samples[:-2], samples[1:-1]))))
    chief_need(lease['positiveMarkerObserved'] is True and lease['naturalExpiryObserved'] is True and (lease['ttlSourceSeconds'] == 90) and (lease['redisModified'] is False) and (lease['scope'] == 'GRACE_DELAYED_RECOVERY_NOT_IMMEDIATE_RECONNECT'))
    binding = lease['redisBinding']
    chief_need(binding['host'] == 'redis' and type(binding['port']) is int and (binding['port'] == 6379) and (type(binding['database']) is int) and (binding['database'] == 0) and (binding['effectiveConfigurationValidated'] is True))
    chief_need(binding['databaseSource'] == 'Jedis.Protocol.DEFAULT_DATABASE' and binding['redisContainerId'] == lease['redisContainerId'] == e['redisContainerId'] and (binding['networkId'] == e['redisNetworkId']))
    for key, value in e['maintenanceSourceHashes'].items():
        chief_need(chief_sha(value) and (binding[key] if key in ('keySourceSha256', 'stringKeySourceSha256') else lease[key]) == value)
    chief_need(all((chief_sha(lease[k]) for k in ('sessionIdSha256', 'closedKeySha256', 'unregisterLineSha256', 'disconnectLineSha256'))))
    cleanup = r['cleanupOrder']
    chief_need(cleanup['processStoppedBeforeDelete'] is True and cleanup['deleteCallCompleted'] is True and (cleanup['deleteEvidenceSource'] == 'server-log-only-not-client-status'))
    proc = cleanup['processIdentity']
    chief_need(set(proc) == {'pid', 'startTicks', 'pgid', 'sid', 'uid'} and all((chief_integer(v, 0 if k == 'uid' else 1) for k, v in proc.items())) and (proc['pid'] == proc['pgid'] == proc['sid']))
    chief_need(all((type(lease['daemonIdentity'].get(k)) is int and lease['daemonIdentity'][k] == v for k, v in proc.items())))
    chief_need(r['cleanupProcessProof'] == dict(originalRuntimeStopCompleted=True, processLeaderExited=True, processGroupEmpty=True) and all((type(v) is bool for v in r['cleanupProcessProof'].values())))
    for key in ('stdout', 'file'):
        chief_need(chief_integer(r['cleanupLogStart'][key], 0) and chief_integer(r['cleanupLogEnd'][key], 0) and (r['cleanupLogStart'][key] <= r['cleanupLogEnd'][key]))
    return dict(executorId=ids['executorId'], workspaceId=ids['workspaceId'], conversationId=ids['conversationId'], ownedTurnIds=[t['turnId'] for t in turns], auditIds=sorted(all_audit | config_ids), checks=list(CHIEF_ALL_CHECKS))

def chief_record(value, required, optional=()):
    """Versioned fixed keys; never retain an unrecognized nested field."""
    chief_need(isinstance(value, dict) and set(required) <= set(value) <= set(required) | set(optional))
    return {key: value[key] for key in required}

def chief_scalar(value):
    chief_need(value is None or type(value) in (int, bool) or (isinstance(value, str) and len(value) <= 512))
    return value

def chief_flat(value, required, optional=()):
    result = chief_record(value, required, optional)
    return {k: chief_scalar(v) for k, v in result.items()}
CHIEF_CHECKPOINT_KEYS = ('restoreStatus', 'fullBytesVerified', 'loadedCurrentIdentityVerified', 'sourceCommit', 'sourceTree', 'invocationSha256', 'composeProject', 'appContainerId', 'appImageId', 'originalJarSha256', 'releaseMutationAttempted', 'releaseJournalCreated', 'runtimeStarted', 'modelStarted')

def chief_omitted_metadata(raw, selected):
    """Recognized but never exported original metadata has a fixed shape too."""
    for k, v in raw.items():
        if k in selected:
            continue
        if k == 'argv':
            chief_need(isinstance(v, list) and len(v) <= 256 and all((isinstance(x, str) and len(x) <= 32768 for x in v)))
        elif k == 'fileSha256':
            chief_need(isinstance(v, dict) and len(v) <= 32 and all((isinstance(n, str) and re.fullmatch('[A-Za-z0-9_.-]+', n) and chief_sha(h) for n, h in v.items())))
        elif k == 'protectedBeforeSha256':
            chief_need(isinstance(v, dict) and set(v) == {'runtime.json', 'result.json', 'runtime-dispatch.json'} and all((chief_sha(h) for h in v.values())))
        elif k == 'restoreCheckpoint':
            chief_flat(v, CHIEF_CHECKPOINT_KEYS)
        elif k == 'linuxMountParents':
            chief_need(isinstance(v, dict) and set(v) == {'/chief-fixture', '/chief-output', '/chief-leases'})
            for parent in v.values():
                chief_flat(parent, ('uid', 'gid', 'mode', 'device', 'inode'))
        elif k == 'hostMountParents':
            chief_need(isinstance(v, dict) and set(v) == {'output', 'fixture', 'lease'})
            for parent in v.values():
                chief_flat(parent, ('path', 'uid', 'gid', 'mode', 'device', 'inode'))
        elif k == 'linuxMountParentExpectation':
            chief_flat(v, ('uid', 'gid', 'mode'))
        elif k in ('elapsedSeconds', 'restoreDrainSeconds'):
            chief_need(type(v) in (int, float) and 0 <= v <= 10000)
        else:
            chief_scalar(v)

def chief_project_chief_inputs(report, wrapper, invocation, checkpoint, journal):
    try:
        return chief__project_chief_inputs(report, wrapper, invocation, checkpoint, journal)
    except (KeyError, TypeError, AttributeError, IndexError):
        raise ValueError('CHIEF_PROJECTION_REFUSED') from None

def chief_validate_chief_projection(value, expected):
    """Consumer-side exact safe schema plus deep facts; no raw report accepted."""
    try:
        chief_need(isinstance(value, dict) and set(value) == {'schemaVersion', 'report', 'wrapper', 'invocation', 'checkpoint', 'journal'} and (type(value['schemaVersion']) is int) and (value['schemaVersion'] == 1))
        canonical = chief__project_chief_inputs(value['report'], value['wrapper'], value['invocation'], value['checkpoint'], value['journal'], projected=True)
        chief_need(canonical == value)
        return chief_validate_chief_success(value['report'], value['wrapper'], value['invocation'], value['checkpoint'], value['journal'], expected)
    except (KeyError, TypeError, AttributeError, IndexError):
        raise ValueError('CHIEF_PROJECTION_REFUSED') from None

def chief__project_chief_inputs(report, wrapper, invocation, checkpoint, journal, projected=False):
    """Whitelist projection; caller separately binds raw refs/provenance and context.

    Raw instructions, CLI sessions, argv, mount paths, and retained preimage paths
    are recognized source fields but never copied. This function does no I/O.
    """
    source_keys = ('commit', 'tree', 'dirty')
    report_keys = ('scene', 'verdict', 'source', 'attemptStarted', 'runtimeStarted', 'modelStarted', 'modelExecution', 'releaseMutationAttempted', 'releaseMutationObserved', 'fullRestoreVerified', 'processCleanup', 'protectedInputsUnchanged', 'restoreStatus', 'dialogue', 'maintenanceWindow', 'cleanupOrder', 'cleanupProcessProof', 'cleanupLogStart', 'cleanupLogEnd', 'checks')
    r = chief_record(report, report_keys, ('fixtureCleanup', 'linuxMountParents'))
    chief_omitted_metadata(report, report_keys)
    for key in set(report_keys) - {'source', 'dialogue', 'maintenanceWindow', 'cleanupOrder', 'cleanupProcessProof', 'cleanupLogStart', 'cleanupLogEnd', 'checks'}:
        chief_scalar(r[key])
    r['source'] = chief_flat(r['source'], source_keys)
    chief_need(isinstance(r['checks'], list) and all((isinstance(v, str) and v in CHIEF_ALL_CHECKS for v in r['checks'])))
    dk = ('verdict', 'scope', 'ownedIds', 'oldIdentity', 'currentIdentity', 'oldTurns', 'newTurns', 'requiredChecks', 'configurationHttpAudit', 'oldPhaseAudit', 'currentPhaseAudit', 'oldAuditLogInterval', 'currentAuditLogInterval')
    raw_dialogue = r['dialogue']
    d = chief_record(raw_dialogue, dk, ('fixtureCleanup', 'mixedServerOldRuntimeRollback'))
    r['dialogue'] = d
    chief_omitted_metadata(raw_dialogue, dk)
    for key in ('verdict', 'scope'):
        chief_scalar(d[key])
    d['ownedIds'] = chief_flat(d['ownedIds'], ('workspaceId', 'userId', 'agentId', 'agentVersionId', 'executorId', 'conversationId'))
    for k in ('oldIdentity', 'currentIdentity'):
        d[k] = chief_record(d[k], ('fingerprint', 'officialIds'))
        chief_scalar(d[k]['fingerprint'])
        chief_need(isinstance(d[k]['officialIds'], list) and all((isinstance(v, str) and re.fullmatch('official:[a-z0-9-]+', v) for v in d[k]['officialIds'])))
    chief_need(isinstance(d['requiredChecks'], list) and all((v in CHIEF_DIALOGUE_CHECKS for v in d['requiredChecks'])))

    def project_tool(value):
        result = chief_record(value, ('auditIds', 'operations', 'scope', 'resultEvidence', 'rawArgumentsOrResultsCaptured'))
        chief_unique(result['auditIds'])
        chief_need(isinstance(result['operations'], list) and all((v in ('autowonder.list_workitems', 'autowonder.list_squads') for v in result['operations'])))
        result['scope'] = chief_flat(result['scope'], ('workspaceId', 'actorId', 'agentId', 'agentVersionId', 'executorId', 'conversationId', 'turnId', 'dispatchAttempt'))
        chief_scalar(result['resultEvidence'])
        chief_scalar(result['rawArgumentsOrResultsCaptured'])
        return result

    def project_log(value):
        return chief_flat(value, ('fromBytes', 'toBytes', 'intervalSha256', 'auditWriteFailures', 'rawLogsPersisted'))
    for k in ('oldTurns', 'newTurns'):
        chief_need(isinstance(d[k], list) and len(d[k]) <= 5)
        items = []
        for raw in d[k]:
            session_key = 'sessionSha256' if projected else 'session'
            t = chief_record(raw, ('turnId', session_key, 'tool', 'contextRecalled', 'package', 'toolAudit', 'windowAudit', 'auditLogInterval', 'completionProof', 'auditWindow', 'isolationBefore', 'isolationAfter'), ('sampleLimitEvidence',))
            if 'sampleLimitEvidence' in raw:
                chief_scalar(raw['sampleLimitEvidence'])
            if projected:
                chief_need(chief_sha(t['sessionSha256']))
            else:
                chief_need(isinstance(t['session'], str) and 0 < len(t['session']) <= 512)
                t['sessionSha256'] = hashlib.sha256(t.pop('session').encode()).hexdigest()
            for field in ('turnId', 'tool', 'contextRecalled'):
                chief_scalar(t[field])
            p = chief_record(t['package'], ('sha256', 'skills', 'optimizerAlias'), ('optimizerInstructions',))
            t['package'] = p
            if 'optimizerInstructions' in raw['package']:
                chief_need(raw['package']['optimizerInstructions'] is None or (isinstance(raw['package']['optimizerInstructions'], str) and len(raw['package']['optimizerInstructions']) <= 2097152))
            chief_need(isinstance(p['skills'], list) and len(p['skills']) <= 32)
            for skill in p['skills']:
                chief_omitted_metadata(skill, ('id', 'name'))
            p['skills'] = [chief_flat(skill, ('id', 'name'), ('path', 'version', 'required', 'sha256')) for skill in p['skills']]
            chief_scalar(p['sha256'])
            chief_scalar(p['optimizerAlias'])
            t['toolAudit'] = project_tool(t['toolAudit'])
            wa = chief_record(t['windowAudit'], ('auditIds', 'submitHttpAuditId', 'toolAudit'))
            wa['toolAudit'] = project_tool(wa['toolAudit'])
            chief_unique(wa['auditIds'])
            chief_scalar(wa['submitHttpAuditId'])
            t['windowAudit'] = wa
            t['auditLogInterval'] = project_log(t['auditLogInterval'])
            proof = chief_record(t['completionProof'], ('conversation', 'inbound', 'outbound'))
            proof['conversation'] = chief_flat(proof['conversation'], ('id', 'workspaceId', 'ownerUserId', 'agentId', 'agentVersionId', 'executorId'))
            proof['inbound'] = chief_flat(proof['inbound'], ('id', 'direction', 'status', 'submittedUserId', 'dispatchAttempt'))
            proof['outbound'] = chief_flat(proof['outbound'], ('id', 'direction', 'status'))
            t['completionProof'] = proof
            for field in ('isolationBefore', 'isolationAfter'):
                t[field] = chief_flat(t[field], ('workspaceId', 'memberCount', 'minUserId', 'maxUserId', 'squadCount'))
            chief_window(t['auditWindow'], wa['auditIds'])
            items.append(t)
        d[k] = items
    chief_need(isinstance(d['configurationHttpAudit'], list) and len(d['configurationHttpAudit']) <= 4)
    d['configurationHttpAudit'] = [chief_record(p, ('path', 'auditIds', 'auditWindow')) for p in d['configurationHttpAudit']]
    for p in d['configurationHttpAudit']:
        chief_scalar(p['path'])
        chief_unique(p['auditIds'])
        chief_window(p['auditWindow'], p['auditIds'])
    for k in ('oldPhaseAudit', 'currentPhaseAudit'):
        d[k] = chief_record(d[k], ('beforeAuditId', 'afterAuditId', 'auditIds', 'noAdditionalWorkspaceOperations', 'rawArgumentsOrResultsCaptured'))
        chief_unique(d[k]['auditIds'])
        for field in set(d[k]) - {'auditIds'}:
            chief_scalar(d[k][field])
    for k in ('oldAuditLogInterval', 'currentAuditLogInterval'):
        d[k] = project_log(d[k])
    lease_keys = ('pttlSamples', 'positiveMarkerObserved', 'naturalExpiryObserved', 'ttlSourceSeconds', 'redisModified', 'scope', 'redisBinding', 'daemonIdentity', 'sessionIdSha256', 'lastActualDisconnectTime', 'unregisterLineSha256', 'disconnectLineSha256', 'ttlSourceSha256', 'redisContainerId', 'redisConfigSourceSha256', 'closedKeySha256')
    lease = chief_record(r['maintenanceWindow'], lease_keys)
    r['maintenanceWindow'] = lease
    for k in set(lease_keys) - {'pttlSamples', 'redisBinding', 'daemonIdentity'}:
        chief_scalar(lease[k])
    chief_need(isinstance(lease['pttlSamples'], list) and len(lease['pttlSamples']) <= 122 and all((type(v) is int for v in lease['pttlSamples'])))
    lease['redisBinding'] = chief_flat(lease['redisBinding'], ('host', 'port', 'database', 'databaseSource', 'networkId', 'redisContainerId', 'effectiveConfigurationValidated', 'keySourceSha256', 'stringKeySourceSha256'))
    lease['daemonIdentity'] = chief_flat(lease['daemonIdentity'], ('pid', 'ppid', 'pgid', 'sid', 'uid', 'startTicks', 'state'))
    co = chief_record(r['cleanupOrder'], ('processStoppedBeforeDelete', 'deleteCallCompleted', 'deleteEvidenceSource', 'processIdentity'))
    for k in set(co) - {'processIdentity'}:
        chief_scalar(co[k])
    co['processIdentity'] = chief_flat(co['processIdentity'], ('pid', 'startTicks', 'pgid', 'sid', 'uid'))
    r['cleanupOrder'] = co
    r['cleanupProcessProof'] = chief_flat(r['cleanupProcessProof'], ('originalRuntimeStopCompleted', 'processLeaderExited', 'processGroupEmpty'))
    for k in ('cleanupLogStart', 'cleanupLogEnd'):
        r[k] = chief_flat(r[k], ('stdout', 'file'))
    wk = ('mode', 'actualExitCode', 'acceptanceVerdict', 'modelStarted', 'modelExecution', 'restorationVerified', 'protectedInputsUnchanged', 'interruptedOrTimedOut', 'ownedContainerResidue', 'source', 'postSource')
    w = chief_record(wrapper, wk, ('argv', 'attemptStarted', 'authorizationReference', 'cancellationRequested', 'dockerContext', 'dockerSocket', 'elapsedSeconds', 'fileSha256', 'forceRequired', 'image', 'logSha256', 'nativePrerequisiteValidated', 'ownedContainer', 'prepared', 'protectedBeforeSha256', 'reportSha256', 'restoreCheckpoint', 'restoreDrainSeconds', 'serviceMutation', 'serviceMutationAttempted', 'signedUrlsRewritten', 'stateDir', 'termAttempted', 'termDelivered'))
    chief_omitted_metadata(wrapper, wk)
    for k in set(wk) - {'source', 'postSource'}:
        chief_scalar(w[k])
    w['source'] = chief_flat(w['source'], source_keys)
    w['postSource'] = chief_flat(w['postSource'], source_keys)
    inv = chief_record(invocation, ('source', 'nativeProof', 'composeProject'), ('acceptanceVerdict', 'appImage', 'authorization', 'fileSha256', 'fixturePlanSha256', 'hostMountParents', 'imageReference', 'legacyJarSha256', 'linuxMountParentExpectation', 'manifestSha256', 'protectedBeforeSha256', 'restoreJarSha256', 'state', 'toolingImage'))
    chief_omitted_metadata(invocation, ('source', 'nativeProof', 'composeProject'))
    inv['source'] = chief_flat(inv['source'], source_keys)
    chief_scalar(inv['composeProject'])
    inv['nativeProof'] = chief_flat(inv['nativeProof'], ('receiptSha256', 'reportSha256', 'logSha256', 'runtimeManifestSha256', 'lifecycleResultSha256'), ('receiptPath',))
    chief_need(all((chief_sha(v) for v in inv['nativeProof'].values())))
    if 'receiptPath' in invocation['nativeProof']:
        chief_scalar(invocation['nativeProof']['receiptPath'])
    cp = chief_flat(checkpoint, CHIEF_CHECKPOINT_KEYS)
    chief_need(isinstance(journal, list) and 3 <= len(journal) <= 32)
    rows = []
    for raw in journal:
        phase = raw.get('phase')
        chief_need(phase in ('bound', 'captured', 'stage-intent', 'swapped', 'restart-intent', 'restarted', 'loaded'))
        fields = {'bound': ('runId', 'phase', 'containerId', 'imageId', 'composeProject', 'sourceCommit', 'sourceTree', 'dbIdentity', 'originalSha', 'fixtureSha'), 'captured': ('runId', 'phase', 'metadata', 'fullPreimageSha'), 'stage-intent': ('runId', 'phase', 'goal', 'expectedSha'), 'swapped': ('runId', 'phase', 'goal', 'jarSha'), 'restart-intent': ('runId', 'phase', 'goal'), 'restarted': ('runId', 'phase', 'goal', 'exitCode'), 'loaded': ('runId', 'phase', 'goal', 'startedAt', 'jarSha', 'loadedIdentityVerified')}[phase]
        row = chief_record(raw, fields, ('fullPreimagePath',) if phase == 'captured' else ('stage',) if phase == 'stage-intent' else ())
        chief_omitted_metadata(raw, fields)
        for k in set(row) - {'dbIdentity', 'metadata'}:
            chief_scalar(row[k])
        if phase == 'captured':
            row['metadata'] = chief_flat(row['metadata'], ('uid', 'gid', 'mode'))
        if phase == 'bound':
            db = chief_record(row['dbIdentity'], ('containerId', 'imageId', 'volumes'))
            chief_scalar(db['containerId'])
            chief_scalar(db['imageId'])
            chief_need(isinstance(db['volumes'], list) and len(db['volumes']) <= 4)
            db['volumes'] = [chief_flat(v, ('destination', 'name')) for v in db['volumes']]
            row['dbIdentity'] = db
        rows.append(row)
    return json.loads(json.dumps({'schemaVersion': 1, 'report': r, 'wrapper': w, 'invocation': inv, 'checkpoint': cp, 'journal': rows}))

context_SOURCE_PATHS = {'ttlSourceSha256': 'src/main/java/com/aliyun/autowonder/websocket/PresenceManager.java', 'redisConfigSourceSha256': 'src/main/java/com/aliyun/autowonder/configuration/RedisConfig.java', 'keySourceSha256': 'src/main/java/com/aliyun/autowonder/executor/ExecutorDispatchSnapshot.java', 'stringKeySourceSha256': 'src/main/java/com/aliyun/autowonder/redis/AbstractRedisManager.java'}
context_NATIVE_KEYS = {'receiptSha256', 'reportSha256', 'logSha256', 'runtimeManifestSha256', 'lifecycleResultSha256'}
context_EXPECTED_KEYS = {'source', 'oldIdentity', 'currentIdentity', 'appCid', 'appImage', 'originalJarSha256', 'fixtureJarSha256', 'dbIdentity', 'composeProject', 'invocationSha256', 'nativeProof', 'redisContainerId', 'redisNetworkId', 'maintenanceSourceHashes'}
context_REF_NAMES = {name + suffix for name in ('git-head', 'git-tree', 'git-status', 'runtime.json', 'result.json', 'runtime-dispatch.json', 'current-pointer', 'app-ps', 'app-inspect', 'mysql-ps', 'mysql-inspect', 'redis-ps', 'redis-inspect', 'app-jar-sha') for suffix in ('', '-recheck')} | {'launch-constants', 'fixture-plan', 'fixture-restore-exact.jar', 'fixture-legacy-definition-fixture.jar', 'official-views-helper', 'redis-binding-helper'} | {'source-' + key for key in context_SOURCE_PATHS}
context_CLOSURE_KEYS = {'sourceRechecked', 'stackIdentityRechecked', 'protectedFilesUnchanged', 'fixtureFilesUnchanged', 'jarShaRechecked'}

def context_need(value):
    if not value:
        raise ValueError('CHIEF_CONTEXT_REFUSED')

def context_sha(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None

def context_image(value):
    return isinstance(value, str) and value.startswith('sha256:') and context_sha(value[7:])

def context_identity(value):
    context_need(isinstance(value, dict) and set(value) == {'commit', 'tree', 'dirty'} and (value['dirty'] is False) and all((isinstance(value[k], str) and re.fullmatch('[0-9a-f]{40}', value[k]) for k in ('commit', 'tree'))))

def context_validate_expected_context(e):
    try:
        return context__validate_expected_context(e)
    except (KeyError, TypeError, AttributeError, IndexError):
        raise ValueError('CHIEF_CONTEXT_REFUSED') from None

def context_validate_chief_expected_context(e):
    """Public pure safe-schema spelling for the unified evidence consumer."""
    return context_validate_expected_context(e)

def context__validate_expected_context(e):
    context_need(isinstance(e, dict) and set(e) == context_EXPECTED_KEYS)
    context_identity(e['source'])
    context_need(all((context_sha(e[k]) for k in ('appCid', 'originalJarSha256', 'fixtureJarSha256', 'invocationSha256', 'redisContainerId', 'redisNetworkId'))) and context_image(e['appImage']))
    context_need(isinstance(e['composeProject'], str) and re.fullmatch('aw-e2e-' + e['source']['commit'][:10] + '-[0-9a-f]{8}', e['composeProject']))
    context_need(isinstance(e['nativeProof'], dict) and set(e['nativeProof']) == context_NATIVE_KEYS and all((context_sha(v) for v in e['nativeProof'].values())))
    context_need(isinstance(e['maintenanceSourceHashes'], dict) and set(e['maintenanceSourceHashes']) == set(context_SOURCE_PATHS) and all((context_sha(v) for v in e['maintenanceSourceHashes'].values())))
    for name in ('oldIdentity', 'currentIdentity'):
        v = e[name]
        context_need(isinstance(v, dict) and set(v) == {'fingerprint', 'officialIds'} and context_sha(v['fingerprint']) and isinstance(v['officialIds'], list) and (1 <= len(v['officialIds']) <= 16) and (len(v['officialIds']) == len(set(v['officialIds']))) and all((isinstance(x, str) and re.fullmatch('official:[a-z0-9-]+', x) for x in v['officialIds'])))
    db = e['dbIdentity']
    context_need(isinstance(db, dict) and set(db) == {'containerId', 'imageId', 'volumes'} and context_sha(db['containerId']) and context_image(db['imageId']))
    context_need(isinstance(db['volumes'], list) and len(db['volumes']) == 1)
    v = db['volumes'][0]
    context_need(isinstance(v, dict) and set(v) == {'destination', 'name'} and (v['destination'] == '/var/lib/mysql') and (v['name'] == e['composeProject'] + '_mysql-data'))
    return json.loads(json.dumps(e))

def validate_chief_expected_context(value):
    return context_validate_chief_expected_context(value)

_validate=validate
def validate(state,app_log,file_log,_prepared_root=None):
    try:return _validate(state,app_log,file_log,_prepared_root)
    except (KeyError,TypeError,AttributeError,IndexError):
        raise ValueError('SUPPLEMENT_CLEANUP_EVIDENCE_REFUSED')from None

if __name__=='__main__':
    import sys
    print(json.dumps(validate(*sys.argv[1:])))
