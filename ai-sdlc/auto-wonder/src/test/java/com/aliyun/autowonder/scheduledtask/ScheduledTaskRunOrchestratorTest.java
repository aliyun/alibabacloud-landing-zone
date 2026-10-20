package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.aliyun.autowonder.common.error.BizException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void humanResumeSnapshotFailureIsBilingualWhileBackgroundPersistenceIsRaw() {
        ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
        DispatchService dispatch = mock(DispatchService.class);
        ScheduledTaskRunDO run = queuedRun(null); run.setExecutionSnapshotJson("{bad json");
        when(runs.findById(1L, 77L)).thenReturn(run);
        DispatchDO paused = new DispatchDO(); paused.setId(700L); paused.setStatus("PAUSED"); paused.setAgentId(20L);
        when(dispatch.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN, 77L)).thenReturn(java.util.List.of(paused));
        var service = orchestrator(runs, dispatch);
        BizException failure = assertThrows(BizException.class, () -> service.resumePaused(1L, 77L, 9L));
        assertBilingual(failure, "execution snapshot is invalid", "执行快照不合法", "Execution snapshot is invalid");
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            service.start(1L, 77L, 9L);
            verify(runs).updateTerminalResult(1L, 77L, "QUEUED", "FAILED", null, "30005: execution snapshot is invalid", 0, 9L);
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
        verify(dispatch, never()).enqueueScheduledResume(anyLong(), anyLong(), any(), anyLong(), anyInt(), anyLong(), anyBoolean(), anyLong());
    }

    private void assertBilingual(BizException failure, String diagnostic, String chineseText, String english) {
        assertEquals(diagnostic, failure.getMessage());
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var chinese = handler.handleBiz(failure);
            assertEquals(200, chinese.getStatusCodeValue());
            assertEquals(chineseText, chinese.getBody().getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var translated = handler.handleBiz(failure);
            assertEquals(200, translated.getStatusCodeValue());
            assertEquals(failure.getCode(), translated.getBody().getCode());
            assertEquals(english, translated.getBody().getMessage());
            assertEquals(diagnostic, failure.getLocalizedMessage());
            assertEquals(diagnostic, com.aliyun.autowonder.common.result.Result.fail(failure.getCode(), diagnostic).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    @Test
    void humanResumeRejectsChangedSnapshotIdentityAndFrozenContextsBeforeMutation() {
        java.util.List<java.util.function.Consumer<JSONObject>> corruptions = java.util.List.of(
                snapshot -> snapshot.put("schemaVersion", "future"),
                snapshot -> snapshot.getJSONObject("task").put("instructionMd", " "),
                snapshot -> snapshot.getJSONObject("task").put("id", 999),
                snapshot -> snapshot.put("agentContexts", new com.aliyun.autowonder.json.JSONArray()),
                snapshot -> snapshot.getJSONArray("agentContexts").add(snapshot.getJSONArray("agentContexts").get(0)));
        String[] diagnostic = {"execution snapshot schema is invalid", "execution snapshot is incomplete", "execution snapshot is incomplete", "frozen agent context is missing", "agent context is duplicated"};
        String[] chinese = {"执行快照格式不合法", "执行快照不完整", "执行快照不完整", "冻结数字员工上下文缺失", "数字员工上下文重复"};
        String[] english = {"Execution snapshot schema is invalid", "Execution snapshot is incomplete", "Execution snapshot is incomplete", "Frozen agent context is missing", "Agent context is duplicated"};
        org.junit.jupiter.api.Assertions.assertAll(java.util.stream.IntStream.range(0, corruptions.size()).mapToObj(i ->
                (org.junit.jupiter.api.function.Executable) () -> {
                    ScheduledTaskRunDao runs = mock(ScheduledTaskRunDao.class);
                    DispatchService dispatch = mock(DispatchService.class);
                    ScheduledTaskRunDO run = queuedRun(null);
                    JSONObject snapshot = JSON.parseObject(run.getExecutionSnapshotJson());
                    corruptions.get(i).accept(snapshot); run.setExecutionSnapshotJson(snapshot.toJSONString());
                    when(runs.findById(1L, 77L)).thenReturn(run);
                    DispatchDO paused = new DispatchDO(); paused.setId(700L); paused.setStatus("PAUSED"); paused.setAgentId(20L);
                    when(dispatch.listBySource(1L, ExecutionSourceType.SCHEDULED_TASK_RUN, 77L)).thenReturn(java.util.List.of(paused));
                    BizException failure = assertThrows(BizException.class, () -> orchestrator(runs, dispatch).resumePaused(1L, 77L, 9L));
                    assertEquals("30005", failure.getCode());
                    org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
                    assertBilingual(failure, diagnostic[i], chinese[i], english[i]);
                    verify(runs, never()).initializeExecution(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong());
                }));
    }

    @Test
    void ownerInactiveFirstObservedExplicitAndDirectEventsUseRecipientLanguageWithoutChangingError() {
        var runs = mock(ScheduledTaskRunDao.class);
        var original = queuedRun(null); var current = queuedRun(null);
        when(runs.findById(1L, 77L)).thenReturn(original, current);
        when(runs.updateTerminalResult(1L, 77L, "QUEUED", "FAILED", null, "OWNER_INACTIVE", 0, 9L)).thenReturn(1);
        var recipient = new com.aliyun.autowonder.notification.RecipientNotificationTestSupport();
        recipient.preference(9L, "\"en-US\"");
        var redis = mock(com.aliyun.autowonder.redis.RedisManager.class);
        var notifications = new ScheduledTaskNotificationService(recipient.service, null, redis, runs);
        var runService = new ScheduledTaskRunService(runs); runService.setObservability(notifications, null);
        var service = new ScheduledTaskRunOrchestrator(runs, mock(DispatchService.class));
        service.setRunService(runService);
        var users = mock(com.aliyun.autowonder.user.UserDao.class);
        var members = mock(com.aliyun.autowonder.workspace.WorkspaceMemberDao.class);
        service.setOwnerDependencies(users, members, null, null, recipient.service);
        service.setNotificationService(notifications);
        service.start(1L, 77L, 9L);
        assertEquals(3, recipient.rows.size());
        assertEquals(List.of("SCHEDULED_TASK_RUN_FAILED","SCHEDULED_TASK_RUN_FAILED","SCHEDULED_TASK_OWNER_INACTIVE"),recipient.rows.stream().map(row->row.getType()).toList());
        assertEquals(List.of("Scheduled task run status updated","Scheduled task run status updated","Scheduled task paused"),recipient.rows.stream().map(row->row.getTitle()).toList());
        for(var row:recipient.rows){assertEquals(77L,row.getRefId());assertEquals("SCHEDULED_TASK_RUN",row.getRefType());assertEquals(9L,row.getRecipientId());assertEquals("UNREAD",row.getStatus());assertNull(row.getLink());}
        verify(redis,times(2)).publish(eq("scheduled-run:77"),anyString());
        assertEquals("OWNER_INACTIVE", current.getError());
        assertEquals("FAILED", current.getStatus());
        assertEquals(1, current.getVersion());
        verify(runs).updateTerminalResult(1L, 77L, "QUEUED", "FAILED", null, "OWNER_INACTIVE", 0, 9L);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals("Task owner is unavailable", recipient.rows.get(0).getContent()),
                () -> assertEquals("Task owner is unavailable", recipient.rows.get(1).getContent()),
                () -> assertEquals("Task owner is unavailable", recipient.rows.get(2).getContent()));
    }

}
