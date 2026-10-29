package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchPauseServiceTest {

    private DispatchDao dispatchDao;
    private DispatchCheckpointService checkpointService;
    private DispatchControlTransport transport;
    private DispatchPauseService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        checkpointService = mock(DispatchCheckpointService.class);
        transport = mock(DispatchControlTransport.class);
        service = new DispatchPauseService(dispatchDao, checkpointService, transport);
    }

    @Test
    void requestsPauseForRunningDispatchAndSendsControlFrame() {
        DispatchDO running = dispatch(55L, DispatchStatus.RUNNING, 0);
        when(dispatchDao.findById(55L)).thenReturn(running);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSING),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(9L)))
                .thenReturn(1);

        DispatchDO paused = service.requestPause(100L, 200L, 55L, 9L);

        assertEquals(DispatchStatus.PAUSING, paused.getStatus());
        verify(transport).pause(paused);
    }

    @Test
    void workitemPauseRejectsScheduledRunDispatch() {
        DispatchDO scheduled = dispatch(55L, DispatchStatus.RUNNING, 0);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(55L)).thenReturn(scheduled);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.requestPause(100L, 200L, 55L, 9L));

        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
        verifyNoInteractions(transport);
    }

    @Test
    void repeatedPauseRequestIsIdempotentAndRedeliversCommand() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.findById(55L)).thenReturn(pausing);

        DispatchDO result = service.requestPause(100L, 200L, 55L, 9L);

        assertSame(pausing, result);
        verify(transport).pause(pausing);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void retryAfterPauseFailureClearsOldError() {
        DispatchDO failed = dispatch(55L, DispatchStatus.PAUSE_FAILED, 1);
        failed.setError("暂停确认超时");
        when(dispatchDao.findById(55L)).thenReturn(failed);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSING),
                isNull(), isNull(), isNull(), isNull(), eq(""), eq(1), eq(9L)))
                .thenReturn(1);

        DispatchDO result = service.requestPause(100L, 200L, 55L, 9L);

        assertEquals(DispatchStatus.PAUSING, result.getStatus());
        assertNull(result.getError());
        verify(transport).pause(result);
    }

    @Test
    void sendFailureAfterPausingMarksDispatchPauseFailed() {
        DispatchDO running = dispatch(55L, DispatchStatus.RUNNING, 0);
        when(dispatchDao.findById(55L)).thenReturn(running);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSING),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(9L)))
                .thenReturn(1);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSE_FAILED),
                isNull(), isNull(), isNull(), isNull(), contains("WebSocket pause send failed"),
                eq(1), eq(9L))).thenReturn(1);
        doThrow(new IllegalStateException("WebSocket pause send failed"))
                .when(transport).pause(any(DispatchDO.class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requestPause(100L, 200L, 55L, 9L));

        assertEquals("WebSocket pause send failed", error.getMessage());
        assertEquals(DispatchStatus.PAUSE_FAILED, running.getStatus());
        assertEquals("WebSocket pause send failed", running.getError());
    }

    @Test
    void acceptsPausedOnlyAfterDurableCheckpointReceipt() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        pausing.setExecutorId(7L);
        when(dispatchDao.findById(55L)).thenReturn(pausing);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L)))
                .thenReturn(1);

        assertTrue(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));
        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L));
    }

    @Test
    void acceptsLateDurablePauseAfterConfirmationTimeoutAndClearsFailure() {
        DispatchDO failed = dispatch(55L, DispatchStatus.PAUSE_FAILED, 2);
        failed.setError("PAUSE_CONFIRMATION_MISSING: timeout");
        when(dispatchDao.findById(55L)).thenReturn(failed);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), eq(""), eq(2), eq(0L)))
                .thenReturn(1);

        assertTrue(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));
        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), eq(""), eq(2), eq(0L));
    }

    @Test
    void latePauseStillRequiresReceiptAndCannotReviveCanceledOrTerminalExecution() {
        for (String status : java.util.List.of(DispatchStatus.PAUSE_FAILED, DispatchStatus.CANCELED,
                DispatchStatus.SUCCEEDED, DispatchStatus.FAILED, DispatchStatus.TIMEOUT)) {
            when(dispatchDao.findById(55L)).thenReturn(dispatch(55L, status, 2));
            assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:missing"), status);
        }
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        service.setRecovery(recovery);
        when(dispatchDao.findById(55L)).thenReturn(dispatch(55L, DispatchStatus.PAUSE_FAILED, 2));
        when(recovery.cancelRequested(100L, 55L)).thenReturn(true);
        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void rechecksExecutorOwnershipAfterTakingControlLock() {
        DispatchDO original = dispatch(55L, DispatchStatus.PAUSE_FAILED, 2);
        DispatchDO reassigned = dispatch(55L, DispatchStatus.PAUSE_FAILED, 3);
        reassigned.setExecutorId(8L);
        when(dispatchDao.findById(55L)).thenReturn(original, reassigned);

        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));
        verifyNoInteractions(checkpointService);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void executorPauseCompletionRemainsSourceAgnostic() {
        DispatchDO scheduled = dispatch(55L, DispatchStatus.PAUSING, 1);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(55L)).thenReturn(scheduled);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L)))
                .thenReturn(1);

        assertTrue(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));
    }

    @Test
    void successfulCompletionBecomesPauseBoundaryWhenPauseRacesWithResult() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.findById(55L)).thenReturn(pausing);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L)))
                .thenReturn(1);

        assertEquals(DispatchPauseService.CompletionDisposition.PAUSED,
                service.onCompletedWhilePausing(100L, 7L, 55L, 42L, "sha256:abc"));
    }

    @Test
    void ordinaryRunningCompletionIsNotConsumedByPauseBoundary() {
        DispatchDO running = dispatch(55L, DispatchStatus.RUNNING, 1);
        when(dispatchDao.findById(55L)).thenReturn(running);

        assertEquals(DispatchPauseService.CompletionDisposition.NOT_PAUSING,
                service.onCompletedWhilePausing(100L, 7L, 55L, 42L, "sha256:abc"));
    }

    @Test
    void rejectsPausedAckWithoutDurableCheckpoint() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        pausing.setExecutorId(7L);
        when(dispatchDao.findById(55L)).thenReturn(pausing);

        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:missing"));
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void recordsPauseFailureWithoutPretendingDispatchIsPaused() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        pausing.setExecutorId(7L);
        when(dispatchDao.findById(55L)).thenReturn(pausing);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSE_FAILED),
                isNull(), isNull(), isNull(), isNull(), eq("checkpoint upload failed"),
                eq(1), eq(0L))).thenReturn(1);

        assertTrue(service.onPauseFailed(100L, 7L, 55L, "checkpoint upload failed"));
    }

    @Test
    void expiresStalePausingDispatchWithRecoverableError() {
        DispatchDO stale = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.failStalePausing(eq(55L), eq(100L), eq(10_000L),
                startsWith(DispatchFailureReason.PAUSE_CONFIRMATION_MISSING + ":"), eq(0L))).thenReturn(1);

        assertTrue(service.expireTimedOutPause(stale, 10_000L));
    }

    @Test
    void doesNotExpireDispatchRefreshedByHeartbeatOrAlreadyLeftPausingState() {
        DispatchDO stale = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.failStalePausing(anyLong(), anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(0);

        assertFalse(service.expireTimedOutPause(stale, 10_000L));
    }

    @Test
    void repeatedPauseAcknowledgementConfirmsWhenControlIsNotCancelled() {
        DispatchDO paused = dispatch(55L, DispatchStatus.PAUSED, 2);
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        service.setRecovery(recovery);
        when(dispatchDao.findById(55L)).thenReturn(paused);
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);

        assertTrue(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));

        verify(checkpointService).confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc");
    }

    @Test
    void repeatedPauseAcknowledgementRejectsAReassignedExecutor() {
        DispatchDO paused = dispatch(55L, DispatchStatus.PAUSED, 2);
        when(dispatchDao.findById(55L)).thenReturn(paused, null);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);

        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));

        verifyNoInteractions(checkpointService);
    }

    @Test
    void completionRejectsPauseWhenTheDurableReceiptDoesNotMatch() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.findById(55L)).thenReturn(pausing);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:stale"))
                .thenReturn(false);

        assertEquals(DispatchPauseService.CompletionDisposition.REJECTED,
                service.onCompletedWhilePausing(100L, 7L, 55L, 42L, "sha256:stale"));

        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void repeatedPauseAcknowledgementConfirmsTheSameBoundaryIdempotently() {
        DispatchDO paused = dispatch(55L, DispatchStatus.PAUSED, 2);
        when(dispatchDao.findById(55L)).thenReturn(paused);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);

        assertTrue(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));

        verify(checkpointService).confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc");
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void repeatedPauseAcknowledgementRejectsCancellationRequestedAfterPause() {
        DispatchDO paused = dispatch(55L, DispatchStatus.PAUSED, 2);
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        service.setRecovery(recovery);
        when(dispatchDao.findById(55L)).thenReturn(paused);
        when(recovery.cancelRequested(100L, 55L)).thenReturn(true);

        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));

        verify(recovery).lockWorkitemControl(paused);
        verifyNoInteractions(checkpointService);
    }

    @Test
    void repeatedPauseAcknowledgementRejectsStateChangedUnderTheControlLock() {
        DispatchDO paused = dispatch(55L, DispatchStatus.PAUSED, 2);
        DispatchDO canceled = dispatch(55L, DispatchStatus.CANCELED, 3);
        when(dispatchDao.findById(55L)).thenReturn(paused, canceled);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);

        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));

        verifyNoInteractions(checkpointService);
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void repeatedPauseAcknowledgementRejectsWhenTheDurableReceiptDoesNotMatch() {
        DispatchDO paused = dispatch(55L, DispatchStatus.PAUSED, 2);
        when(dispatchDao.findById(55L)).thenReturn(paused);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:stale"))
                .thenReturn(false);

        assertFalse(service.onPaused(100L, 7L, 55L, 42L, "sha256:stale"));
    }

    @Test
    void pauseTransitionConflictMustRetryInsteadOfAcknowledging() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.findById(55L)).thenReturn(pausing);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L)))
                .thenReturn(0);

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));

        assertTrue(conflict.getMessage().contains("must retry"));
    }

    @Test
    void completionPauseTransitionConflictMustRetryInsteadOfAcknowledging() {
        DispatchDO pausing = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.findById(55L)).thenReturn(pausing);
        when(checkpointService.confirmPauseReceipt(100L, 7L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L)))
                .thenReturn(0);

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> service.onCompletedWhilePausing(100L, 7L, 55L, 42L, "sha256:abc"));

        assertTrue(conflict.getMessage().contains("must retry"));
    }

    private DispatchDO dispatch(long id, String status, int version) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setSdlcStepId(300L);
        dispatch.setAgentId(400L);
        dispatch.setExecutorId(7L);
        dispatch.setStatus(status);
        dispatch.setVersion(version);
        return dispatch;
    }

    @Test
    void workitemPauseRejectsHistoricalExecutionUsingSharedPolicy() {
        DispatchDO historical = dispatch(55L, DispatchStatus.RUNNING, 0);
        historical.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchDO latest = dispatch(56L, DispatchStatus.RUNNING, 0);
        latest.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        when(dispatchDao.findById(55L)).thenReturn(historical);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(java.util.List.of(historical, latest));
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(recovery.closed(100L, 200L)).thenReturn(false);
        service.setRecovery(recovery);
        service.setPresenceManager(mock(com.aliyun.autowonder.websocket.PresenceManager.class));

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.requestPause(100L, 200L, 55L, 9L));

        verify(transport, never()).pause(any());
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void workitemPauseAllowsSameWorkerWaitingReworkBarrier() {
        DispatchDO active = dispatch(55L, DispatchStatus.RUNNING, 0);
        active.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchDO rework = dispatch(56L, DispatchStatus.WAITING_FOR_PAUSE, 0);
        rework.setSourceType(ExecutionSourceType.WORKITEM.name());
        rework.setResultSummary("waitForDispatchId=55");
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        com.aliyun.autowonder.websocket.PresenceManager presence =
                mock(com.aliyun.autowonder.websocket.PresenceManager.class);
        when(dispatchDao.findById(55L)).thenReturn(active);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(java.util.List.of(active, rework));
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSING),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(0L))).thenReturn(1);
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(recovery.closed(100L, 200L)).thenReturn(false);
        when(presence.isExecutorOnline(7L)).thenReturn(true);
        service.setRecovery(recovery);
        service.setPresenceManager(presence);

        DispatchDO result = service.requestPause(100L, 200L, 55L, 0L);

        assertEquals(DispatchStatus.PAUSING, result.getStatus());
        verify(transport).pause(result);
    }

    @Test
    void workitemPauseStillRejectsAWaitingReworkForAnotherExecution() {
        DispatchDO active = dispatch(55L, DispatchStatus.RUNNING, 0);
        active.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchDO rework = dispatch(56L, DispatchStatus.WAITING_FOR_PAUSE, 0);
        rework.setSourceType(ExecutionSourceType.WORKITEM.name());
        rework.setResultSummary("waitForDispatchId=99");
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        when(dispatchDao.findById(55L)).thenReturn(active);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(java.util.List.of(active, rework));
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(recovery.closed(100L, 200L)).thenReturn(false);
        service.setRecovery(recovery);
        service.setPresenceManager(mock(com.aliyun.autowonder.websocket.PresenceManager.class));

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.requestPause(100L, 200L, 55L, 0L));
        verify(transport, never()).pause(any());
    }

    @Test
    void workitemPauseRejectsLatestRuntimeFailureWithSameRuleAsReadModel() {
        DispatchDO running = dispatch(55L, DispatchStatus.RUNNING, 0);
        running.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        DispatchRuntimeEventDao events = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(55L)).thenReturn(running);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(java.util.List.of(running));
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(recovery.closed(100L, 200L)).thenReturn(false);
        DispatchRuntimeEventDO failed = new DispatchRuntimeEventDO();
        failed.setId(1L);
        failed.setEventType("step.failed");
        when(events.listByDispatchInArrivalOrder(100L, 55L)).thenReturn(java.util.List.of(failed));
        com.aliyun.autowonder.websocket.PresenceManager presence =
                mock(com.aliyun.autowonder.websocket.PresenceManager.class);
        when(presence.isExecutorOnline(7L)).thenReturn(true);
        service.setRecovery(recovery);
        service.setRuntimeEvents(events);
        service.setPresenceManager(presence);

        com.aliyun.autowonder.common.error.BizException failure =
                assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                        () -> service.requestPause(100L, 200L, 55L, 9L));
        assertTrue(failure.getMessage().contains("执行已失败"));

        verify(transport, never()).pause(any());
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(),
                any(), any(), any(), any(), any(), anyInt(), anyLong());
        verify(recovery, never()).recordControlRequest(any(), anyString(), anyString(), anyString(),
                anyLong(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void workitemPauseAllowsWhenNewerRuntimeEventSupersedesFailure() {
        DispatchDO running = dispatch(55L, DispatchStatus.RUNNING, 0);
        running.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        DispatchRuntimeEventDao events = mock(DispatchRuntimeEventDao.class);
        when(dispatchDao.findById(55L)).thenReturn(running);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(java.util.List.of(running));
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSING),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(9L))).thenReturn(1);
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(recovery.closed(100L, 200L)).thenReturn(false);
        DispatchRuntimeEventDO failed = new DispatchRuntimeEventDO();
        failed.setId(1L);
        failed.setEventType("step.failed");
        DispatchRuntimeEventDO recovered = new DispatchRuntimeEventDO();
        recovered.setId(2L);
        recovered.setEventType("step.started");
        when(events.listByDispatchInArrivalOrder(100L, 55L)).thenReturn(java.util.List.of(failed, recovered));
        com.aliyun.autowonder.websocket.PresenceManager presence =
                mock(com.aliyun.autowonder.websocket.PresenceManager.class);
        when(presence.isExecutorOnline(7L)).thenReturn(true);
        service.setRecovery(recovery);
        service.setRuntimeEvents(events);
        service.setPresenceManager(presence);

        DispatchDO result = service.requestPause(100L, 200L, 55L, 9L);

        assertEquals(DispatchStatus.PAUSING, result.getStatus());
        verify(transport).pause(result);
    }

    @Test
    void workitemPauseRejectsExplicitlyOfflineExecutor() {
        DispatchDO running = dispatch(55L, DispatchStatus.RUNNING, 0);
        running.setSourceType(ExecutionSourceType.WORKITEM.name());
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        com.aliyun.autowonder.websocket.PresenceManager presence =
                mock(com.aliyun.autowonder.websocket.PresenceManager.class);
        when(dispatchDao.findById(55L)).thenReturn(running);
        when(dispatchDao.listByWorkitem(100L, 200L)).thenReturn(java.util.List.of(running));
        when(recovery.cancelRequested(100L, 55L)).thenReturn(false);
        when(recovery.closed(100L, 200L)).thenReturn(false);
        when(presence.isExecutorOnline(7L)).thenReturn(false);
        service.setRecovery(recovery);
        service.setPresenceManager(presence);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.requestPause(100L, 200L, 55L, 9L));

        verify(transport, never()).pause(any());
    }

    @Test
    void pauseConflictIsBilingualAtBoundaryWithoutChangingDiagnosticOrStatus() {
        DispatchDO source = dispatch(55L, DispatchStatus.SUCCEEDED, 0); when(dispatchDao.findById(55L)).thenReturn(source);
        var ex = assertThrows(com.aliyun.autowonder.common.error.BizException.class, () -> service.requestPause(100L,200L,55L,9L));
        assertEquals("当前执行状态不能暂停", ex.getMessage());
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            assertEquals("The current execution cannot be paused in this state.", com.aliyun.autowonder.common.result.Result.fail(ex).getMessage());
            assertEquals("SUCCEEDED", source.getStatus()); verifyNoInteractions(transport);
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
    }

}
