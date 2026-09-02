package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.agent.AgentRepoPermDO;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.agent.AgentSkillDO;
import com.aliyun.autowonder.agent.AgentMemoryRefDao;
import com.aliyun.autowonder.agent.AgentMemoryRefDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoRelationDao;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.memory.MemoryDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ScheduledTaskTriggerServiceTest {
    private ScheduledTaskRunDao runDao;
    private ScheduledTaskTriggerService trigger;
    private ArtifactDao artifactDao;
    private InMemoryObjectStorage storage;
    private ScheduledTaskDao taskDao;

    @BeforeEach
    void setUp() {
        runDao = mock(ScheduledTaskRunDao.class);
        artifactDao = mock(ArtifactDao.class);
        SquadMemberDao squadDao = mock(SquadMemberDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao versionDao = mock(AgentVersionDao.class);
        SquadMemberDO member = new SquadMemberDO(); member.setTenantId(1L); member.setAgentId(11L);
        when(squadDao.listBySquad(2L)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO(); agent.setId(11L); agent.setTenantId(1L); agent.setOnlineVersionId(12L);
        when(agentDao.findById(11L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO(); version.setId(12L); version.setTenantId(1L); version.setAgentId(11L); version.setRoleName("QA"); version.setRoleCode("QA"); version.setSdlcId(51L);
        when(versionDao.findById(12L)).thenReturn(version);
        when(artifactDao.listBySource(anyLong(), anyString(), anyLong(), anyString())).thenReturn(List.of());
        storage = new InMemoryObjectStorage();
        trigger = new ScheduledTaskTriggerService(runDao, artifactDao, squadDao, agentDao, versionDao, storage);
        taskDao = mock(ScheduledTaskDao.class);
        when(taskDao.findByIdForUpdate(1L, 9L)).thenAnswer(invocation -> task());
        trigger.setTaskDao(taskDao);
        AgentRepoPermDao repoPermDao = mock(AgentRepoPermDao.class); RepoDao repoDao = mock(RepoDao.class);
        RepoRelationDao relationDao = mock(RepoRelationDao.class); AgentSkillDao skillDao = mock(AgentSkillDao.class);
        SkillDao skillCatalogDao = mock(SkillDao.class); AgentMemoryRefDao memoryRefDao = mock(AgentMemoryRefDao.class); MemoryDao memoryDao = mock(MemoryDao.class);
        AgentRepoPermDO permission = new AgentRepoPermDO(); permission.setTenantId(1L); permission.setRepoId(21L); permission.setPermLevel("WRITE");
        when(repoPermDao.listByVersion(12L)).thenReturn(List.of(permission));
        RepoDO repo = new RepoDO(); repo.setId(21L); repo.setTenantId(1L); repo.setName("service"); repo.setUrl("git://service"); repo.setDefaultBranch("main"); when(repoDao.findById(21L)).thenReturn(repo);
        when(relationDao.listByRepoId(1L, 21L)).thenReturn(List.of());
        AgentSkillDO binding = new AgentSkillDO(); binding.setTenantId(1L); binding.setSkillId(31L); when(skillDao.listByVersion(12L)).thenReturn(List.of(binding));
        SkillDO skill = new SkillDO(); skill.setId(31L); skill.setTenantId(1L); skill.setType("PLUGIN"); skill.setName("tester"); skill.setPackageOssRef("bucket/plugin.tgz"); skill.setPackageMd5("abc"); skill.setInstallSpec("{\"providers\":[\"mcp\"]}"); when(skillCatalogDao.findById(31L)).thenReturn(skill);
        AgentMemoryRefDO ref = new AgentMemoryRefDO(); ref.setTenantId(1L); ref.setMemoryId(41L); when(memoryRefDao.listByVersion(12L)).thenReturn(List.of(ref));
        MemoryDO memory = new MemoryDO(); memory.setTenantId(1L); memory.setStatus("ADOPTED"); memory.setContentMd("frozen memory"); when(memoryDao.findById(41L)).thenReturn(memory);
        when(squadDao.listByAgent(11L)).thenReturn(List.of(member));
        trigger.setSnapshotDependencies(repoPermDao, repoDao, relationDao, skillDao, skillCatalogDao, memoryRefDao, memoryDao);
        SdlcStepDao steps = mock(SdlcStepDao.class); SdlcStepDO step = new SdlcStepDO(); step.setId(61L); step.setTenantId(1L); step.setSdlcId(51L); step.setStepOrder(1); step.setName("test"); step.setKind("TEST"); step.setInstructionMd("execute"); step.setChecklistJson("[\"check\"]"); step.setGatePolicyJson("{\"review\":true}"); when(steps.listBySdlc(51L)).thenReturn(List.of(step)); trigger.setSdlcStepDao(steps);
    }
    @Test
    void buildsStableTriggerKeys() {
        Instant due = Instant.parse("2026-08-10T18:00:00Z");
        assertEquals("task:9:scheduled:2026-08-10T18:00:00Z",
                ScheduledTaskTriggerService.scheduledKey(9L, due));
        assertEquals("task:9:manual:request-1",
                ScheduledTaskTriggerService.manualKey(9L, "request-1"));
    }

    @Test
    void duplicateScheduledTriggerReturnsTenantScopedExistingRun() {
        ScheduledTaskRunDO existing = new ScheduledTaskRunDO(); existing.setId(77L);
        doThrow(new DuplicateKeyException("duplicate")).when(runDao).insert(any());
        when(runDao.findByTriggerKey(1L, "task:9:scheduled:2026-08-10T18:00:00Z")).thenReturn(existing);
        assertEquals(77L, trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now()).getId());
        verify(runDao).findByTriggerKey(1L, "task:9:scheduled:2026-08-10T18:00:00Z");
    }

    @Test
    void nonDuplicateInsertFailurePropagatesWithoutRecoveryLookup() {
        doThrow(new DataIntegrityViolationException("invalid")).when(runDao).insert(any());
        assertThrows(DataIntegrityViolationException.class,
                () -> trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now()));
        verify(runDao, never()).findByTriggerKey(anyLong(), anyString());
    }

    @Test
    void overlapSkipCreatesAuditableSkippedRun() {
        ScheduledTaskRunDO active = new ScheduledTaskRunDO(); active.setStatus("RUNNING");
        when(runDao.findActiveByTaskForUpdate(1L, 9L)).thenReturn(List.of(active));
        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());
        verify(runDao).insert(argThat(run -> "SKIPPED".equals(run.getStatus()) && "OVERLAP".equals(run.getSkipReason())));
    }

    @Test
    void queueOverlapKeepsTheNewRunQueuedForLaterExecution() {
        ScheduledTaskRunDO active = new ScheduledTaskRunDO(); active.setStatus("RUNNING");
        when(runDao.findActiveByTask(1L, 9L)).thenReturn(List.of(active));
        ScheduledTaskDO queued = task(); queued.setOverlapPolicy("QUEUE");

        trigger.fireScheduled(queued, Instant.parse("2026-08-10T18:00:00Z"), Instant.now());

        verify(runDao).insert(argThat(run -> "QUEUED".equals(run.getStatus()) && run.getSkipReason() == null));
    }

    @Test
    void eligibleNonQueueRunStartsThroughTheSharedOrchestrator() {
        ScheduledTaskRunOrchestrator orchestrator = mock(ScheduledTaskRunOrchestrator.class);
        trigger.setRunOrchestrator(orchestrator);
        doAnswer(invocation -> { invocation.<ScheduledTaskRunDO>getArgument(0).setId(77L); return null; })
                .when(runDao).insert(any());

        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());

        verify(orchestrator).start(1L, 77L, 3L);
    }

    @Test
    void continuousSessionNeverAllowsConcurrentRunEvenWithAllowPolicy() {
        ScheduledTaskRunDO active = new ScheduledTaskRunDO(); active.setStatus("RUNNING");
        when(runDao.findActiveByTaskForUpdate(1L, 9L)).thenReturn(List.of(active));
        ScheduledTaskDO continuous = task(); continuous.setSessionMode("CONTINUOUS"); continuous.setOverlapPolicy("ALLOW");
        when(taskDao.findByIdForUpdate(1L, 9L)).thenReturn(continuous);

        trigger.fireScheduled(continuous, Instant.parse("2026-08-10T18:00:00Z"), Instant.now());

        verify(taskDao).findByIdForUpdate(1L, 9L);
        verify(runDao).insert(argThat(run -> "SKIPPED".equals(run.getStatus()) && "OVERLAP".equals(run.getSkipReason())));
    }

    @Test
    void skipPolicyLocksTaskBeforeCheckingActiveRunsAndInsertingDecision() {
        ScheduledTaskDO locked = task();
        when(taskDao.findByIdForUpdate(1L, 9L)).thenReturn(locked);
        ScheduledTaskRunDO active = new ScheduledTaskRunDO(); active.setStatus("RUNNING");
        when(runDao.findActiveByTaskForUpdate(1L, 9L)).thenReturn(List.of(active));

        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());

        var ordering = inOrder(taskDao, runDao);
        ordering.verify(taskDao).findByIdForUpdate(1L, 9L);
        ordering.verify(runDao).findActiveByTaskForUpdate(1L, 9L);
        ordering.verify(runDao).insert(any());
    }

    @Test
    void expiredMisfireIsRecordedWithStartDeadlineRatherThanPolicySkip() {
        trigger.fireMisfire(task(), Instant.parse("2026-08-10T17:00:00Z"), Instant.now(), true);

        verify(runDao).insert(argThat(run -> "SKIPPED".equals(run.getStatus())
                && "START_DEADLINE".equals(run.getSkipReason())));
    }

    @Test
    void snapshotContainsStrictV1AgentLedger() {
        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());
        verify(runDao).insert(argThat(run -> {
            var json = JSON.parseObject(run.getExecutionSnapshotJson());
            return "autowonder.scheduledTaskExecutionSnapshot.v1".equals(json.getString("schemaVersion"))
                    && json.getJSONArray("agentContexts").size() == 1
                    && json.getJSONArray("agentContexts").getJSONObject(0).getLongValue("agentVersionId") == 12L;
        }));
    }

    @Test
    void snapshotFreezesNonEmptyBoundAgentContext() {
        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());
        verify(runDao).insert(argThat(run -> {
            var context = JSON.parseObject(run.getExecutionSnapshotJson()).getJSONArray("agentContexts").getJSONObject(0);
            return context.getJSONArray("repos").getJSONObject(0).getLongValue("repoId") == 21L
                    && "tester".equals(context.getJSONArray("skills").getJSONObject(0).getString("name"))
                    && "bucket/plugin.tgz".equals(context.getJSONArray("skills").getJSONObject(0).getString("packageOssRef"))
                    && context.getJSONArray("skills").getJSONObject(0).getJSONObject("config").containsKey("providers")
                    && "frozen memory".equals(context.getJSONObject("memory").getString("mem_0"))
                    && context.getJSONObject("roster").containsKey("digitalTeammates");
        }));
    }

    @Test
    void snapshotPreservesWriteRepositoryRuntimePolicy() {
        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());

        ArgumentCaptor<ScheduledTaskRunDO> runCaptor = ArgumentCaptor.forClass(ScheduledTaskRunDO.class);
        verify(runDao).insert(runCaptor.capture());
        var repo = JSON.parseObject(runCaptor.getValue().getExecutionSnapshotJson())
                .getJSONArray("agentContexts").getJSONObject(0)
                .getJSONArray("repos").getJSONObject(0);

        assertEquals("eager", repo.getString("mode"));
        assertTrue(repo.getBooleanValue("allowCommit"));
        assertTrue(repo.getBooleanValue("allowPush"));
        assertTrue(repo.getBooleanValue("allowNetwork"));
    }

    @Test
    void snapshotFreezesSdlcAndPackagedSkillDescriptor() {
        // The Trigger must store a self-contained SDLC/capability record; Task6 must never rebuild it later.
        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());
        verify(runDao).insert(argThat(run -> {
            var sdlc = JSON.parseObject(run.getExecutionSnapshotJson()).getJSONObject("sdlc");
            return run.getSdlcId() == 51L && run.getCurrentStepId() == 61L && sdlc.getLongValue("id") == 51L
                    && sdlc.getLongValue("currentStepId") == 61L && "execute".equals(sdlc.getJSONArray("steps").getJSONObject(0).getString("instruction"));
        }));
    }

    @Test
    void refusesRequirementDocumentReturnedFromAnotherExecutionSource() {
        ArtifactDO leakedWorkitemDocument = new ArtifactDO();
        leakedWorkitemDocument.setId(81L);
        leakedWorkitemDocument.setTenantId(1L);
        leakedWorkitemDocument.setWorkitemId(9L);
        leakedWorkitemDocument.setSourceType(ExecutionSourceType.WORKITEM.name());
        leakedWorkitemDocument.setType(RequirementDocumentService.TYPE);
        leakedWorkitemDocument.setName("wrong-owner.md");
        leakedWorkitemDocument.setOssRef("autowonder-artifacts-daily/wrong-owner.md");
        storage.put("autowonder-artifacts-daily", "wrong-owner.md", "not ours".getBytes());
        when(artifactDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK.name(), 9L,
                RequirementDocumentService.TYPE)).thenReturn(List.of(leakedWorkitemDocument));

        assertThrows(BizException.class,
                () -> trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now()));
        verify(runDao, never()).insert(any());
    }

    @Test
    void manualTriggerLoadsOnlyTheRequestedTenantAndRequiresAnActiveTask() {
        when(taskDao.findById(1L, 9L)).thenReturn(task());

        trigger.fireManual(1L, 9L, "request-1");

        verify(runDao).insert(argThat(run -> "MANUAL".equals(run.getTriggerType())
                && "task:9:manual:request-1".equals(run.getTriggerKey())));

        ScheduledTaskDO paused = task();
        paused.setId(10L);
        paused.setStatus("PAUSED");
        when(taskDao.findById(1L, 10L)).thenReturn(paused);
        when(taskDao.findByIdForUpdate(1L, 10L)).thenReturn(paused);
        assertThrows(BizException.class, () -> trigger.fireManual(1L, 10L, "request-2"));
    }

    @Test
    void snapshotHashesRequirementBytesFromScheduledTaskSource() {
        ArtifactDO document = new ArtifactDO();
        document.setId(82L); document.setTenantId(1L); document.setWorkitemId(9L);
        document.setSourceType(ExecutionSourceType.SCHEDULED_TASK.name());
        document.setType(RequirementDocumentService.TYPE); document.setName("requirements.md");
        document.setOssRef("autowonder-artifacts-daily/requirements.md");
        storage.put("autowonder-artifacts-daily", "requirements.md", "frozen bytes".getBytes());
        when(artifactDao.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK.name(), 9L,
                RequirementDocumentService.TYPE)).thenReturn(List.of(document));

        trigger.fireScheduled(task(), Instant.parse("2026-08-10T18:00:00Z"), Instant.now());

        verify(runDao).insert(argThat(run -> "sha256:ed605a86d0d0e2ac7ff2c1910fc7bcf52ac5e52b212f0fe530a7933a7c99f940"
                .equals(JSON.parseObject(run.getExecutionSnapshotJson()).getJSONArray("requirementDocuments")
                        .getJSONObject(0).getString("sha256"))));
    }

    private ScheduledTaskDO task() {
        ScheduledTaskDO t = new ScheduledTaskDO(); t.setId(9L); t.setWorkspaceId(1L); t.setName("nightly"); t.setInstructionMd("run");
        t.setSquadId(2L); t.setInitialAgentId(11L); t.setCreatorId(3L); t.setStatus("ACTIVE"); t.setSessionMode("ISOLATED"); t.setOverlapPolicy("SKIP");
        return t;
    }
}
