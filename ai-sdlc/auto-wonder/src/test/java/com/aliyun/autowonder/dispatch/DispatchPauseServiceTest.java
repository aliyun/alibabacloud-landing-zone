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
        when(checkpointService.matchesDurableReceipt(100L, 55L, 42L, "sha256:abc"))
                .thenReturn(true);
        when(dispatchDao.updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L)))
                .thenReturn(1);

        assertTrue(service.onPaused(100L, 7L, 55L, 42L, "sha256:abc"));
        verify(dispatchDao).updateStatus(eq(55L), eq(100L), eq(DispatchStatus.PAUSED),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(0L));
    }

    @Test
    void executorPauseCompletionRemainsSourceAgnostic() {
        DispatchDO scheduled = dispatch(55L, DispatchStatus.PAUSING, 1);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(55L)).thenReturn(scheduled);
        when(checkpointService.matchesDurableReceipt(100L, 55L, 42L, "sha256:abc"))
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
        when(checkpointService.matchesDurableReceipt(100L, 55L, 42L, "sha256:abc"))
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
                contains("暂停确认超时"), eq(0L))).thenReturn(1);

        assertTrue(service.expireTimedOutPause(stale, 10_000L));
    }

    @Test
    void doesNotExpireDispatchRefreshedByHeartbeatOrAlreadyLeftPausingState() {
        DispatchDO stale = dispatch(55L, DispatchStatus.PAUSING, 1);
        when(dispatchDao.failStalePausing(anyLong(), anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(0);

        assertFalse(service.expireTimedOutPause(stale, 10_000L));
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
}
