package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceResultTest {

    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao runtimeEventDao;
    private ExecutorSelector executorSelector;
    private SdlcDriver sdlcDriver;
    private RedisManager redisManager;
    private AuditLogService auditLogService;
    private ExecutorRegistry executorRegistry;
    private DispatchService service;

    private static final long TENANT = 100L;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        AgentVersionDao agentVersionDao = mock(AgentVersionDao.class);
        executorSelector = mock(ExecutorSelector.class);
        PackageContextAssembler assembler = mock(PackageContextAssembler.class);
        TaskPackager taskPackager = mock(TaskPackager.class);
        DispatchTransport transport = mock(DispatchTransport.class);
        sdlcDriver = mock(SdlcDriver.class);
        redisManager = mock(RedisManager.class);
        auditLogService = mock(AuditLogService.class);
        executorRegistry = mock(ExecutorRegistry.class);
        service = new DispatchService(dispatchDao, runtimeEventDao, workitemDao, agentDao, agentVersionDao,
                executorSelector, assembler, taskPackager, transport, sdlcDriver,
                redisManager, null, auditLogService, executorRegistry);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), anyInt(), anyLong())).thenReturn(1);
        when(sdlcDriver.onSuccess(anyLong(), anyLong(), anyLong())).thenReturn(DriveResult.stop());
        when(sdlcDriver.onFail(anyLong(), anyLong(), anyLong())).thenReturn(DriveResult.stop());
    }

    private DispatchDO at(String status) {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setWorkitemId(200L);
        d.setSdlcStepId(300L);
        d.setAgentId(400L);
        d.setStatus(status);
        d.setAttempt(1);
        d.setVersion(0);
        return d;
    }

    @Test
    void onAckMovesDispatchedToAcked() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.DISPATCHED));
        service.onAck(TENANT, 500L);
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.ACKED),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void onAckIgnoredWhenTerminal() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.SUCCEEDED));
        service.onAck(TENANT, 500L);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void onProgressMovesAckedToRunning() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.ACKED));
        service.onProgress(TENANT, 500L);
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.RUNNING),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void onProgressNoOpWhenAlreadyRunning() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.RUNNING));
        service.onProgress(TENANT, 500L);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void onProgressPersistsRuntimeStepEvent() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.ACKED));
        JSONObject frame = JSONObject.parseObject("""
                {"type":"TASK_PROGRESS","dispatchId":500,"resultSummary":"step.started","stepOrder":3,"stepName":"自测","message":"开始自测"}
                """);

        service.onProgress(TENANT, 500L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> cap = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(cap.capture());
        DispatchRuntimeEventDO event = cap.getValue();
        assertEquals(TENANT, event.getTenantId());
        assertEquals(200L, event.getWorkitemId());
        assertEquals(500L, event.getDispatchId());
        assertEquals(400L, event.getAgentId());
        assertEquals("step.started", event.getEventType());
        assertEquals(3, event.getStepOrder());
        assertEquals("自测", event.getStepName());
        assertEquals("开始自测", event.getMessage());
        verify(auditLogService).record(argThat(record ->
                record.getTenantId() == TENANT
                        && Long.valueOf(400L).equals(record.getActorId())
                        && "AGENT".equals(record.getActorType())
                        && "DISPATCH".equals(record.getModule())
                        && "RUNTIME_EVENT".equals(record.getAction())
                        && "step.started".equals(record.getEventType())));
    }

    @Test
    void onProgressPersistsWorkflowPlanEvent() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.RUNNING));
        JSONObject frame = JSONObject.parseObject("""
                {"type":"TASK_PROGRESS","dispatchId":500,"resultSummary":"workflow.plan_applied",
                 "revision":2,"targetStepId":"coding","steps":[{"stepKey":"coding","planStatus":"RUN"}]}
                """);

        service.onProgress(TENANT, 500L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> cap = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(cap.capture());
        assertEquals("workflow.plan_applied", cap.getValue().getEventType());
        assertTrue(cap.getValue().getDetailJson().contains("\"revision\":2"));
    }

    @Test
    void onProgressTruncatesDatabaseSummaryFields() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.ACKED));
        JSONObject frame = new JSONObject();
        frame.put("type", "TASK_PROGRESS");
        frame.put("resultSummary", "step.started");
        frame.put("message", "进".repeat(1200));
        frame.put("error", "错".repeat(1200));

        service.onProgress(TENANT, 500L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> cap = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(cap.capture());
        assertEquals(1024, cap.getValue().getMessage().codePointCount(0, cap.getValue().getMessage().length()));
        assertEquals(1024, cap.getValue().getError().codePointCount(0, cap.getValue().getError().length()));
    }

    @Test
    void onProgressDoesNotTreatFrameTypeOrStepNameAsRuntimeEventType() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.ACKED));
        JSONObject frame = JSONObject.parseObject("""
                {"type":"TASK_PROGRESS","dispatchId":500,"resultSummary":"step.started","stepOrder":3,"name":"自测","message":"开始自测"}
                """);

        service.onProgress(TENANT, 500L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> cap = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(cap.capture());
        DispatchRuntimeEventDO event = cap.getValue();
        assertEquals("step.started", event.getEventType());
        assertEquals("自测", event.getStepName());
    }

    @Test
    void onResultSuccessTerminatesAndDrivesSuccess() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(running);
        when(dispatchDao.listOldestPendingByAgent(400L, 1)).thenReturn(java.util.List.of());
        service.onResult(TENANT, 9L, 500L, true, "done", null, false);
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.SUCCEEDED),
                any(), any(), any(), eq("done"), isNull(), anyInt(), anyLong());
        verify(sdlcDriver).onSuccess(TENANT, 200L, 300L);
        verify(dispatchDao).listOldestPendingByAgent(400L, 1);
        verify(auditLogService).record(argThat(record ->
                "COMPLETE_DISPATCH".equals(record.getAction())
                        && "dispatch.succeeded".equals(record.getEventType())));
        verify(executorRegistry).markProviderAvailable(9L);
    }

    @Test
    void explicitHandoffCompletesWithoutAlsoDrivingOrdinaryNextStep() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(running);
        when(dispatchDao.listOldestPendingByAgent(400L, 1)).thenReturn(java.util.List.of());

        assertTrue(service.onResult(TENANT, 9L, 500L, true, "done", null,
                false, true));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.SUCCEEDED),
                any(), any(), any(), eq("done"), isNull(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onSuccess(anyLong(), anyLong(), anyLong());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }

    @Test
    void sideInteractionWorkflowPlanCannotAdvanceFormalSdlc() {
        assertDetachedInteractionCannotAdvanceFormalSdlc("SIDE_INTERACTION");
    }

    @Test
    void canonicalInteractionWorkflowPlanCannotAdvanceFormalSdlc() {
        assertDetachedInteractionCannotAdvanceFormalSdlc("CANONICAL_INTERACTION");
    }

    private void assertDetachedInteractionCannotAdvanceFormalSdlc(String resumeMode) {
        DispatchDO interaction = at(DispatchStatus.RUNNING);
        interaction.setExecutorId(9L);
        interaction.setResumeMode(resumeMode);
        when(dispatchDao.findById(500L)).thenReturn(interaction);
        when(dispatchDao.listOldestPendingByAgent(400L, 1)).thenReturn(java.util.List.of());

        assertTrue(service.onResult(TENANT, 9L, 500L, true, "interaction reply", null, true));

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.SUCCEEDED),
                any(), any(), any(), eq("interaction reply"), isNull(), anyInt(), anyLong());
        verify(sdlcDriver, never()).onSuccess(anyLong(), anyLong(), anyLong());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
        verify(dispatchDao).listOldestPendingByAgent(400L, 1);
    }

    @Test
    void onResultFailTerminatesAndDrivesFail() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.RUNNING));
        service.onResult(TENANT, 500L, false, null, "boom");
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                any(), any(), any(), isNull(), eq("boom"), anyInt(), anyLong());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
        verify(auditLogService).record(argThat(record ->
                "FAIL_DISPATCH".equals(record.getAction())
                        && "dispatch.failed".equals(record.getEventType())));
    }

    @Test
    void executorProviderFailureRequeuesSameDispatchWithoutFailingSdlc() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(running);
        when(dispatchDao.returnOwnedActiveToPending(500L, TENANT, 9L, 0, 0L)).thenReturn(1);
        when(dispatchDao.listOldestPendingByAgent(400L, 1)).thenReturn(java.util.List.of());

        assertTrue(service.onExecutorUnavailableResult(TENANT, 9L, 500L,
                "agent_error.provider_quota_limit", "quota exhausted"));

        verify(executorRegistry).markProviderUnavailable(9L, "agent_error.provider_quota_limit");
        verify(dispatchDao).returnOwnedActiveToPending(500L, TENANT, 9L, 0, 0L);
        verify(runtimeEventDao).insert(argThat(event ->
                "dispatch.executor_failover".equals(event.getEventType())
                        && event.getError().contains("agent_error.provider_quota_limit")
                        && event.getError().contains("quota exhausted")
                        && event.getDetailJson().contains("\"executorId\":9")
                        && event.getDetailJson().contains("\"retrying\":true")));
        verify(dispatchDao, never()).listOldestPendingByAgent(anyLong(), anyInt());
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
        verify(dispatchDao, never()).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void firstRuntimeRecoveryFailureRequeuesForAHealthyExecutor() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(running);
        when(redisManager.eval(anyString(), eq(java.util.List.of("dispatch:session-recovery:500")),
                eq(java.util.List.of("86400")))).thenReturn(1L);
        when(dispatchDao.returnOwnedActiveToPending(500L, TENANT, 9L, 0, 0L)).thenReturn(1);

        assertTrue(service.onExecutorUnavailableResult(TENANT, 9L, 500L,
                "runtime_recovery", "session not found"));

        verify(dispatchDao).returnOwnedActiveToPending(500L, TENANT, 9L, 0, 0L);
        verify(sdlcDriver, never()).onFail(anyLong(), anyLong(), anyLong());
    }

    @Test
    void secondRuntimeRecoveryFailureStopsInsteadOfLooping() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(10L);
        when(dispatchDao.findById(500L)).thenReturn(running);
        when(redisManager.eval(anyString(), eq(java.util.List.of("dispatch:session-recovery:500")),
                eq(java.util.List.of("86400")))).thenReturn(2L);

        assertTrue(service.onExecutorUnavailableResult(TENANT, 10L, 500L,
                "runtime_recovery", "session not found again"));

        verify(dispatchDao, never()).returnOwnedActiveToPending(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                any(), any(), any(), isNull(), contains("SESSION_RECOVERY_EXHAUSTED"), anyInt(), anyLong());
        verify(sdlcDriver).onFail(TENANT, 200L, 300L);
    }

    @Test
    void nonProviderCategoryCannotEnterExecutorFailoverPath() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(running);

        assertFalse(service.onExecutorUnavailableResult(TENANT, 9L, 500L,
                "agent_error.unknown", "tests failed"));

        verifyNoInteractions(executorRegistry);
        verify(dispatchDao, never()).returnOwnedActiveToPending(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void duplicateProviderFailureAcceptsConcurrentRequeueWithoutSecondSdlcTransition() {
        DispatchDO running = at(DispatchStatus.RUNNING);
        running.setExecutorId(9L);
        DispatchDO pending = at(DispatchStatus.PENDING);
        pending.setExecutorId(null);
        pending.setVersion(1);
        when(dispatchDao.findById(500L)).thenReturn(running, pending);
        when(dispatchDao.returnOwnedActiveToPending(500L, TENANT, 9L, 0, 0L)).thenReturn(0);

        assertTrue(service.onExecutorUnavailableResult(TENANT, 9L, 500L,
                "agent_error.provider_quota_limit", "quota exhausted"));

        verify(dispatchDao, times(1)).returnOwnedActiveToPending(500L, TENANT, 9L, 0, 0L);
        verifyNoInteractions(sdlcDriver);
    }

    @Test
    void onResultTruncatesErrorToDatabaseLimit() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.RUNNING));
        String oversized = "错".repeat(600);

        service.onResult(TENANT, 500L, false, null, oversized);

        verify(dispatchDao).updateStatus(eq(500L), eq(TENANT), eq(DispatchStatus.FAILED),
                any(), any(), any(), isNull(), argThat(error -> error.codePointCount(0, error.length()) == 512),
                anyInt(), anyLong());
    }

    @Test
    void onResultIgnoredWhenAlreadyTerminal() {
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.SUCCEEDED));
        service.onResult(TENANT, 500L, true, "dup", null);
        verify(sdlcDriver, never()).onSuccess(anyLong(), anyLong(), anyLong());
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void onResultSuccessWithEnqueueStartsNextDispatch() {
        doAnswer(inv -> { ((DispatchDO) inv.getArgument(0)).setId(501L); return null; })
                .when(dispatchDao).insert(any());
        when(dispatchDao.findById(500L)).thenReturn(at(DispatchStatus.RUNNING));
        when(sdlcDriver.onSuccess(TENANT, 200L, 300L)).thenReturn(DriveResult.enqueue(301L, 401L));
        when(dispatchDao.findByIdempotencyKey(eq(TENANT), anyString())).thenReturn(null);
        when(dispatchDao.findById(argThat(id -> id != 500L))).thenReturn(null);
        service.onResult(TENANT, 500L, true, "done", null);
        verify(dispatchDao).insert(argThat(d ->
                d.getSdlcStepId() == 301L && d.getAgentId() == 401L
                        && DispatchStatus.PENDING.equals(d.getStatus())));
    }

    @Test
    void tenantMismatchIsIgnored() {
        DispatchDO d = at(DispatchStatus.DISPATCHED);
        d.setTenantId(999L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        service.onAck(TENANT, 500L);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void duplicateTerminalResultFromOwningExecutorIsAcknowledgable() {
        DispatchDO d = at(DispatchStatus.SUCCEEDED);
        d.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(d);

        assertTrue(service.onResult(TENANT, 9L, 500L, true, "duplicate", null));

        verifyNoInteractions(sdlcDriver);
    }

    @Test
    void resultFromForeignExecutorIsRejected() {
        DispatchDO d = at(DispatchStatus.RUNNING);
        d.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(d);

        assertFalse(service.onResult(TENANT, 10L, 500L, true, "foreign", null));

        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void busyReturnsOnlyOwnedDispatchedRowToPending() {
        DispatchDO d = at(DispatchStatus.DISPATCHED);
        d.setExecutorId(9L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(dispatchDao.returnDispatchedToPending(500L, TENANT, 9L, 0, 0L)).thenReturn(1);

        assertTrue(service.onBusy(TENANT, 9L, 500L));

        verify(dispatchDao).returnDispatchedToPending(500L, TENANT, 9L, 0, 0L);
    }

    @Test
    void heartbeatTouchesOnlyProvidedOwnedDispatches() {
        service.renewActiveLeases(TENANT, 9L, java.util.List.of(500L, 501L));

        verify(executorRegistry).updateRunningDispatches(9L, java.util.List.of(500L, 501L));
        verify(dispatchDao).touchOwnedActive(TENANT, 9L, java.util.List.of(500L, 501L));
    }

    @Test
    void emptyHeartbeatStillRecordsThatExecutorReleasedAllDispatches() {
        service.renewActiveLeases(TENANT, 9L, java.util.List.of());

        verify(executorRegistry).updateRunningDispatches(9L, java.util.List.of());
        verify(dispatchDao, never()).touchOwnedActive(anyLong(), anyLong(), anyList());
    }
}
