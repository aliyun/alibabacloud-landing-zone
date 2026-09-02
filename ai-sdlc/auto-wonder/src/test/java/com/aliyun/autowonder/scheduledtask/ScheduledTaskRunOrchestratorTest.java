package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskRunOrchestratorTest {

    private static ScheduledTaskRunOrchestrator orchestrator(ScheduledTaskRunDao runs, DispatchService dispatch) {
        ScheduledTaskRunOrchestrator orchestrator = new ScheduledTaskRunOrchestrator(runs, dispatch);
        orchestrator.setRunService(new ScheduledTaskRunService(runs));
        return orchestrator;
    }

    @Test
    void pausedHandoffDispatchResumesWithTargetsFrozenVersionBeforeDelivery() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatch = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(91L); run.setCurrentAgentId(30L); run.setSdlcId(52L);
        JSONObject snapshot = JSON.parseObject(run.getExecutionSnapshotJson());
        snapshot.getJSONArray("agentContexts").add(JSON.parseObject("{\"agentId\":30,\"agentVersionId\":402,\"identity\":{\"name\":\"B\"},\"repos\":[],\"repoMap\":{},\"skills\":[],\"memory\":{},\"roster\":{},\"sdlc\":{\"id\":52,\"currentStepId\":91}}"));
        run.setExecutionSnapshotJson(snapshot.toJSONString());
        DispatchDO paused = new DispatchDO(); paused.setId(700L); paused.setStatus("PAUSED"); paused.setAgentId(30L); paused.setSdlcStepId(91L); paused.setAttempt(1);
        DispatchDO continuation = new DispatchDO(); continuation.setId(701L);
        when(runDao.findById(1L, 77L)).thenReturn(run);
        when(dispatch.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN, 77L)).thenReturn(java.util.List.of(paused));
        when(runDao.initializeExecution(1L,77L,"QUEUED",52L,30L,91L,0,9L)).thenAnswer(i -> { run.setStatus("STARTING"); run.setVersion(1); return 1; });
        when(dispatch.enqueueScheduledResume(1L,77L,91L,30L,2,700L,false,9L)).thenReturn(continuation);
        when(runDao.updateStatus(1L,77L,"STARTING","WAITING_EXECUTOR",1,9L)).thenReturn(1);

        assertTrue(orchestrator(runDao, dispatch).resumePaused(1L, 77L, 9L));

        org.mockito.InOrder order = inOrder(dispatch);
        order.verify(dispatch).pinScheduledAgentVersion(701L, 1L, 402L);
        order.verify(dispatch).runPending(701L);
        verify(runDao, never()).updateTerminalResult(anyLong(), anyLong(), any(), any(), any(), contains("30005"), anyInt(), anyLong());
    }

    @Test
    void rootRunCreatesOnePinnedScheduledDispatchWithoutSdlcStep() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(null);
        when(runDao.findById(1L, 77L)).thenReturn(run);
        when(runDao.initializeExecution(eq(1L), eq(77L), eq("QUEUED"), isNull(), eq(20L),
                isNull(), eq(0), eq(9L))).thenAnswer(invocation -> {
                    run.setStatus("STARTING"); run.setVersion(1); return 1;
                });
        DispatchDO dispatch = new DispatchDO(); dispatch.setId(501L); dispatch.setStatus("PENDING");
        when(dispatchService.enqueueSubject(1L, ExecutionSourceType.SCHEDULED_TASK_RUN,
                77L, null, 20L, 1, 9L)).thenReturn(dispatch);
        when(runDao.updateStatus(1L, 77L, "STARTING", "WAITING_EXECUTOR", 1, 9L)).thenReturn(1);

        orchestrator(runDao, dispatchService).start(1L, 77L, 9L);

        verify(dispatchService).enqueueSubject(1L, ExecutionSourceType.SCHEDULED_TASK_RUN,
                77L, null, 20L, 1, 9L);
        verify(dispatchService).pinScheduledAgentVersion(501L, 1L, 401L);
        verify(runDao).updateStatus(1L, 77L, "STARTING", "WAITING_EXECUTOR", 1, 9L);
    }

    @Test
    void invalidSnapshotFailsRunInsteadOfRequeueing() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(null);
        run.setExecutionSnapshotJson("{bad json");
        when(runDao.findById(1L, 77L)).thenReturn(run);

        orchestrator(runDao, dispatchService).start(1L, 77L, 9L);

        verify(runDao).updateTerminalResult(eq(1L), eq(77L), eq("QUEUED"), eq("FAILED"), isNull(),
                contains("30005"), eq(0), eq(9L));
        verifyNoInteractions(dispatchService);
    }

    @Test
    void changedFrozenRequirementDocumentFailsBeforeCreatingDispatch() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        ArtifactDao artifactDao = mock(ArtifactDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ScheduledTaskRunDO run = queuedRun(null);
        JSONObject snapshot = JSON.parseObject(run.getExecutionSnapshotJson());
        snapshot.getJSONArray("requirementDocuments").add(JSON.parseObject("{\"artifactId\":81,\"name\":\"req.md\",\"ossRef\":\"t/1/scheduled-task/12/requirements/req.md\",\"sha256\":\"sha256:deadbeef\"}"));
        run.setExecutionSnapshotJson(snapshot.toJSONString());
        when(runDao.findById(1L, 77L)).thenReturn(run);
        com.aliyun.autowonder.artifact.ArtifactDO artifact = new com.aliyun.autowonder.artifact.ArtifactDO();
        artifact.setId(81L); artifact.setTenantId(1L); artifact.setSourceType(ExecutionSourceType.SCHEDULED_TASK.name());
        artifact.setWorkitemId(12L); artifact.setType("REQUIREMENT_DOC"); artifact.setName("req.md"); artifact.setOssRef("t/1/scheduled-task/12/requirements/req.md");
        when(artifactDao.findBySourceAndId(1L, ExecutionSourceType.SCHEDULED_TASK.name(), 12L, 81L)).thenReturn(artifact);
        when(storage.get(artifact.getOssRef())).thenReturn("changed".getBytes());
        ScheduledTaskRunOrchestrator orchestrator = orchestrator(runDao, dispatchService);
        orchestrator.setDocumentDependencies(artifactDao, storage);

        orchestrator.start(1L, 77L, 9L);

        verify(dispatchService, never()).enqueueSubject(anyLong(), any(), anyLong(), any(), anyLong(), anyInt(), anyLong());
        verify(runDao).updateTerminalResult(eq(1L), eq(77L), eq("QUEUED"), eq("FAILED"), isNull(),
                contains("30005"), eq(0), eq(9L));
    }

    @Test
    void handoffUsesTheTargetAgentsFrozenVersionInsideTheSameRun() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(null);
        JSONObject snapshot = JSON.parseObject(run.getExecutionSnapshotJson());
        snapshot.getJSONArray("agentContexts").add(JSON.parseObject("{\"agentId\":30,\"agentVersionId\":402,\"identity\":{\"name\":\"B\",\"roleCode\":\"qa\"},\"repos\":[],\"repoMap\":{},\"skills\":[],\"memory\":{},\"roster\":{},\"sdlc\":{\"id\":52,\"currentStepId\":91}}"));
        run.setSdlcId(51L); run.setStatus("WAITING_EXECUTOR"); run.setVersion(3); run.setExecutionSnapshotJson(snapshot.toJSONString());
        DispatchDO source = new DispatchDO(); source.setId(700L); source.setTenantId(1L); source.setWorkitemId(77L);
        source.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name()); source.setAgentId(20L); source.setStatus("SUCCEEDED"); source.setAttempt(1);
        DispatchDO downstream = new DispatchDO(); downstream.setId(701L);
        when(runDao.findById(1L, 77L)).thenReturn(run);
        when(runDao.updateCurrentAssignment(1L, 77L, 52L, 30L, 91L, 3, 0L)).thenReturn(1);
        when(dispatchService.enqueueScheduledHandoff(1L, 77L, 91L, 30L, 700L, 2, 0L)).thenReturn(downstream);

        orchestrator(runDao, dispatchService).handoff(source, 30L);

        verify(dispatchService).pinScheduledAgentVersion(701L, 1L, 402L);
    }

    @Test
    void handoffResolvesOfflineTargetOnlyFromFrozenLedger() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class); DispatchService dispatch = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(null); JSONObject snapshot=JSON.parseObject(run.getExecutionSnapshotJson());
        snapshot.getJSONArray("agentContexts").add(JSON.parseObject("{\"agentId\":30,\"agentVersionId\":402,\"identity\":{\"name\":\"Offline B\",\"roleCode\":\"qa\"},\"repos\":[],\"repoMap\":{},\"skills\":[],\"memory\":{},\"roster\":{},\"sdlc\":{\"id\":52,\"currentStepId\":91}}"));
        run.setSdlcId(51L); run.setStatus("WAITING_EXECUTOR"); run.setVersion(1); run.setExecutionSnapshotJson(snapshot.toJSONString());
        DispatchDO source=new DispatchDO(); source.setId(700L); source.setTenantId(1L); source.setWorkitemId(77L); source.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name()); source.setAgentId(20L); source.setStatus("SUCCEEDED"); source.setAttempt(1);
        DispatchDO downstream=new DispatchDO(); downstream.setId(701L);
        when(runDao.findById(1L,77L)).thenReturn(run); when(runDao.updateCurrentAssignment(1L,77L,52L,30L,91L,1,0L)).thenReturn(1);
        when(dispatch.enqueueScheduledHandoff(1L,77L,91L,30L,700L,2,0L)).thenReturn(downstream);

        orchestrator(runDao,dispatch).handoff(source,"qa");

        verify(dispatch).pinScheduledAgentVersion(701L,1L,402L);
    }

    @Test
    void successfulDispatchFinishesRunDirectly() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(61L);
        run.setStatus("WAITING_EXECUTOR"); run.setVersion(2); run.setSdlcId(51L);
        DispatchDO source = new DispatchDO(); source.setId(700L); source.setTenantId(1L); source.setWorkitemId(77L);
        source.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name()); source.setSdlcStepId(61L); source.setAgentId(20L); source.setAttempt(1);
        when(runDao.findById(1L, 77L)).thenReturn(run);

        orchestrator(runDao, dispatchService).onDispatchResult(source, true, "ok", null);

        verify(dispatchService, never()).enqueueSubject(anyLong(), any(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void successfulDispatchFromTargetAgentAlsoFinishesDirectly() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        DispatchService dispatchService = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(91L);
        run.setStatus("WAITING_EXECUTOR"); run.setVersion(2); run.setSdlcId(52L); run.setCurrentAgentId(30L);
        DispatchDO source = new DispatchDO(); source.setId(700L); source.setTenantId(1L); source.setWorkitemId(77L);
        source.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name()); source.setSdlcStepId(91L); source.setAgentId(30L); source.setAttempt(1);
        when(runDao.findById(1L, 77L)).thenReturn(run);

        orchestrator(runDao, dispatchService).onDispatchResult(source, true, "ok", null);

        verify(dispatchService, never()).enqueueSubject(anyLong(), any(), anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    private ScheduledTaskRunDO queuedRun(Long stepId) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(77L); run.setWorkspaceId(1L); run.setScheduledTaskId(12L); run.setStatus("QUEUED");
        run.setInitialAgentId(20L); run.setSquadId(4L); run.setCurrentAgentId(20L);
        run.setCurrentStepId(stepId); run.setOwnerId(9L); run.setVersion(0); run.setSessionMode("ISOLATED");
        JSONObject snapshot = new JSONObject(true);
        snapshot.put("schemaVersion", "autowonder.scheduledTaskExecutionSnapshot.v1");
        snapshot.put("task", JSON.parseObject("{\"id\":12,\"name\":\"nightly\",\"instructionMd\":\"test\"}"));
        snapshot.put("assignment", JSON.parseObject("{\"squadId\":4,\"initialAgentId\":20}"));
        snapshot.put("policies", JSON.parseObject("{\"sessionMode\":\"ISOLATED\",\"overlapPolicy\":\"SKIP\"}"));
        snapshot.put("trigger", JSON.parseObject("{\"type\":\"MANUAL\",\"scheduledAt\":\"2026-08-10T00:00:00Z\"}"));
        snapshot.put("requirementDocuments", JSON.parseArray("[]"));
        snapshot.put("agentContexts", JSON.parseArray("[{\"agentId\":20,\"agentVersionId\":401,\"identity\":{\"name\":\"A\",\"roleCode\":\"dev\"},\"repos\":[],\"repoMap\":{},\"skills\":[],\"memory\":{},\"roster\":{}}]"));
        run.setExecutionSnapshotJson(snapshot.toJSONString());
        return run;
    }
}
