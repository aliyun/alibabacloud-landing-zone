package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchServiceRecoveryTest {

    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao runtimeEvents;
    private RedisManager redisManager;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        redisManager = mock(RedisManager.class);
        runtimeEvents = mock(DispatchRuntimeEventDao.class);
        DispatchService real = new DispatchService(dispatchDao, runtimeEvents,
                mock(WorkitemDao.class), mock(AgentDao.class), mock(AgentVersionDao.class),
                mock(ExecutorSelector.class), mock(PackageContextAssembler.class),
                mock(TaskPackager.class), mock(DispatchTransport.class), mock(SdlcDriver.class),
                redisManager, mock(DispatchCheckpointService.class));
        service = spy(real);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        doReturn(false).when(service).runPending(anyLong());
    }

    @Test
    void runtimeRecoveryEventPreservesSourceAndReplacementIdentity() {
        DispatchDO source = dispatch(55L, DispatchStatus.RUNNING, 2);
        DispatchDO replacement = dispatch(56L, DispatchStatus.PENDING, 3);
        DispatchRuntimeEventDO prior = new DispatchRuntimeEventDO();
        prior.setSeq(41L);
        when(runtimeEvents.listByDispatch(100L, 55L)).thenReturn(List.of(prior));
        var publisher = mock(DispatchLiveActivityPublisher.class);
        service.setLiveActivityPublisher(publisher);
        service.recordRuntimeRecoveryEvent(source, replacement);
        var event = org.mockito.ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEvents).insert(event.capture());
        assertEquals(55L, event.getValue().getDispatchId());
        assertEquals("dispatch.runtime_recovery", event.getValue().getEventType());
        assertEquals(56L, com.aliyun.autowonder.json.JSON.parseObject(
                event.getValue().getDetailJson()).getLong("recoveredDispatchId"));
        assertNotNull(event.getValue().getMessage());
        assertEquals(42L, event.getValue().getSeq());
        verify(publisher).publish(source, event.getValue());
    }

    @Test
    void createsNewFencedRecoveryAttemptFromLatestFailedDispatch() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 2);
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(2);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(56L, recovery.getId());
        assertEquals(3, recovery.getAttempt());
        assertEquals(55L, recovery.getResumeFromDispatchId());
        assertEquals("RECOVERY", recovery.getResumeMode());
        assertEquals("continue:55", recovery.getIdempotencyKey());
        verify(service).runPending(56L);
    }

    @Test
    void staleRunningDispatchIsCancelledBeforeRecovery() {
        DispatchDO source = dispatch(55L, DispatchStatus.RUNNING, 1);
        source.setGmtModified(new Date(System.currentTimeMillis() - 180_000L));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(1);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                any(), any(), any(), any(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        service.continueDispatch(100L, 200L, 55L, 9L);

        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                any(), any(), any(), any(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), anyLong());
    }

    @Test
    void pausedDispatchCanContinueImmediately() {
        DispatchDO source = dispatch(55L, DispatchStatus.PAUSED, 2);
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(2);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                isNull(), isNull(), isNull(), isNull(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), eq(0L))).thenReturn(1);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(55L, recovery.getResumeFromDispatchId());
        assertEquals("RECOVERY", recovery.getResumeMode());
        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                isNull(), isNull(), isNull(), isNull(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), eq(0L));
    }

    @Test
    void legacyWorkitemContinueRejectsScheduledRunDispatchBeforeWorkitemQueries() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 2);
        source.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(55L)).thenReturn(source);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.continueDispatch(100L, 200L, 55L, 9L));

        verify(dispatchDao, never()).listByWorkitem(anyLong(), anyLong());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void terminalInteractionDispatchDoesNotBlockContinueOfLatestFormalExecution() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 2);
        DispatchDO interaction = dispatch(57L, DispatchStatus.SUCCEEDED, 1);
        interaction.setResumeMode("COMMENT_INTERACTION");
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source, interaction));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(2);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(55L, recovery.getResumeFromDispatchId());
        assertEquals("continue:55", recovery.getIdempotencyKey());
    }

    @Test
    void sameStepStaleRowForwardsContinueToLatestFailedRework() {
        DispatchDO stale = dispatch(55L, DispatchStatus.FAILED, 2);
        DispatchDO rework = dispatch(58L, DispatchStatus.FAILED, 3);
        rework.setResumeMode("COMMENT_REWORK");
        when(dispatchDao.findById(55L)).thenReturn(stale);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(stale, rework));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:58")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(3);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(59L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(58L, recovery.getResumeFromDispatchId());
        assertEquals("continue:58", recovery.getIdempotencyKey());
        assertEquals(4, recovery.getAttempt());
        verify(dispatchDao, never()).updateStatus(eq(55L), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void staleNonTerminalInteractionBehindNewerFormalDispatchDoesNotBlockContinue() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 2);
        DispatchDO stuckInteraction = dispatch(57L, DispatchStatus.PAUSED, 1);
        stuckInteraction.setResumeMode("COMMENT_INTERACTION");
        DispatchDO rework = dispatch(58L, DispatchStatus.FAILED, 3);
        rework.setResumeMode("COMMENT_REWORK");
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source, stuckInteraction, rework));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:58")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(3);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(59L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(58L, recovery.getResumeFromDispatchId());
    }

    @Test
    void forwardingConvergesOnExistingRecoveryOfLatestExecution() {
        DispatchDO stale = dispatch(55L, DispatchStatus.FAILED, 2);
        DispatchDO rework = dispatch(58L, DispatchStatus.FAILED, 3);
        rework.setResumeMode("COMMENT_REWORK");
        when(dispatchDao.findById(55L)).thenReturn(stale);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(stale, rework));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:58"))
                .thenReturn(dispatch(59L, DispatchStatus.PENDING, 4));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(59L, recovery.getId());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void crossStepStaleRowContinueRejectedWithLatestDispatchInfo() {
        DispatchDO stale = dispatch(55L, DispatchStatus.FAILED, 2);
        DispatchDO newer = dispatch(58L, DispatchStatus.FAILED, 1);
        newer.setSdlcStepId(400L);
        when(dispatchDao.findById(55L)).thenReturn(stale);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(stale, newer));

        com.aliyun.autowonder.common.error.BizException error =
                assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                        () -> service.continueDispatch(100L, 200L, 55L, 9L));

        assertEquals("10409", error.getCode());
        assertTrue(error.getMessage().contains("dispatchId=58"), error.getMessage());
        assertTrue(error.getMessage().contains("stepId=400"), error.getMessage());
    }

    @Test
    void inFlightInteractionBlocksContinue() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 2);
        DispatchDO interaction = dispatch(57L, DispatchStatus.RUNNING, 1);
        interaction.setResumeMode("COMMENT_INTERACTION");
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source, interaction));

        com.aliyun.autowonder.common.error.BizException error =
                assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                        () -> service.continueDispatch(100L, 200L, 55L, 9L));

        assertEquals("10409", error.getCode());
        assertTrue(error.getMessage().contains("正在处理评论交互"), error.getMessage());
    }

    @Test
    void continueRejectsWhenResolvedTargetAlreadySucceeded() {
        DispatchDO source = dispatch(55L, DispatchStatus.SUCCEEDED, 2);
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);

        com.aliyun.autowonder.common.error.BizException error =
                assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                        () -> service.continueDispatch(100L, 200L, 55L, 9L));

        assertTrue(error.getMessage().contains("仍在线或已成功"), error.getMessage());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void continueRejectsWhenTargetCancelTransitionLosesCasRace() {
        DispatchDO source = dispatch(55L, DispatchStatus.RUNNING, 1);
        source.setGmtModified(new Date(System.currentTimeMillis() - 180_000L));
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.CANCELED),
                any(), any(), any(), any(), eq(DispatchFailureReason.MANUAL_CONTINUE),
                eq(0), anyLong())).thenReturn(0);

        com.aliyun.autowonder.common.error.BizException error =
                assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                        () -> service.continueDispatch(100L, 200L, 55L, 9L));

        assertTrue(error.getMessage().contains("执行状态已变化"), error.getMessage());
        verify(dispatchDao, never()).insert(any());
    }

    @Test
    void interactionSourceStillContinuesWhenItIsTheLatestExecution() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 1);
        source.setResumeMode("COMMENT_INTERACTION");
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source));
        when(dispatchDao.findByIdempotencyKey(100L, "continue:55")).thenReturn(null);
        when(dispatchDao.findMaxAttempt(100L, 200L, 300L)).thenReturn(1);
        doAnswer(invocation -> {
            DispatchDO inserted = invocation.getArgument(0);
            inserted.setId(56L);
            return null;
        }).when(dispatchDao).insert(any(DispatchDO.class));

        DispatchDO recovery = service.continueDispatch(100L, 200L, 55L, 9L);

        assertEquals(55L, recovery.getResumeFromDispatchId());
    }

    @Test
    void interactionSourceStillRejectedWhenNotLatest() {
        DispatchDO source = dispatch(55L, DispatchStatus.FAILED, 1);
        source.setResumeMode("COMMENT_INTERACTION");
        DispatchDO newer = dispatch(57L, DispatchStatus.FAILED, 2);
        when(dispatchDao.findById(55L)).thenReturn(source);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(List.of(source, newer));

        com.aliyun.autowonder.common.error.BizException error =
                assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                        () -> service.continueDispatch(100L, 200L, 55L, 9L));

        assertTrue(error.getMessage().contains("最新一次执行"), error.getMessage());
    }

    private DispatchDO dispatch(long id, String status, int attempt) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setSdlcStepId(300L);
        dispatch.setAgentId(400L);
        dispatch.setStatus(status);
        dispatch.setAttempt(attempt);
        dispatch.setVersion(0);
        dispatch.setGmtModified(new Date());
        return dispatch;
    }

    @Test
    void recoveryLockAndPinErrorsRenderEnglishWithoutChangingDiagnosticCode() {
        when(redisManager.tryAcquireLock(anyString(),anyString(),anyLong())).thenReturn(false);
        var ex = assertThrows(com.aliyun.autowonder.common.error.BizException.class, () -> service.continueDispatch(100L,200L,55L,9L));
        assertEquals("恢复请求正在处理中", ex.getMessage());
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            assertEquals("A recovery request is already being processed.", com.aliyun.autowonder.common.result.Result.fail(ex).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
    }
    @Test
    void lostPinKeepsRawIdAndCodeWhileBoundaryIsBilingual() {
        com.aliyun.autowonder.common.error.BizException ex = org.springframework.test.util.ReflectionTestUtils.invokeMethod(DispatchService.class, "pinLost", 55L);
        assertEquals("scheduled dispatch version pin was lost: dispatchId=55", ex.getMessage());
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            assertEquals("The scheduled dispatch version pin was lost (dispatchId=55).", com.aliyun.autowonder.common.result.Result.fail(ex).getMessage());
            assertEquals("30005", ex.getCode());
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
    }

}
