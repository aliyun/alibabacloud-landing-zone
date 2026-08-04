package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchCompensationTaskTest {

    private DispatchDao dispatchDao;
    private DispatchService dispatchService;
    private DispatchPauseService pauseService;
    private RedisManager redisManager;
    private InteractionWorkflowService interactionWorkflowService;
    private DispatchCompensationTask task;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        dispatchService = mock(DispatchService.class);
        pauseService = mock(DispatchPauseService.class);
        redisManager = mock(RedisManager.class);
        interactionWorkflowService = mock(InteractionWorkflowService.class);
        task = new DispatchCompensationTask(dispatchDao, dispatchService, pauseService,
                interactionWorkflowService, redisManager);
    }

    private DispatchDO row(long id, long tenantId, long workitemId, long stepId, String status) {
        DispatchDO d = new DispatchDO();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setWorkitemId(workitemId);
        d.setSdlcStepId(stepId);
        d.setStatus(status);
        return d;
    }

    @Test
    void skipsWhenLockNotAcquired() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        task.sweep();
        verify(dispatchDao, never()).listStuck(anyList(), anyLong(), anyInt());
    }

    @Test
    void redrivesStuckPendingAndTimesOutStuckRunning() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PENDING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(1L, 100L, 200L, 300L, DispatchStatus.PENDING)));
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED,
                DispatchStatus.RUNNING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(2L, 101L, 201L, 301L, DispatchStatus.RUNNING)));

        task.sweep();

        verify(dispatchService).runPending(1L);
        verify(dispatchService).onTimeout(101L, 2L);
        verify(redisManager).releaseLock(eq("dispatch:compensation:lock"), anyString());
    }

    @Test
    void returnsStuckPackagingToPendingRatherThanTimingOutTheWorkitem() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PACKAGING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(5L, 102L, 205L, 305L, DispatchStatus.PACKAGING)));

        task.sweep();
        long afterSweep = System.currentTimeMillis();

        var cutoff = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(dispatchDao).listStuck(eq(List.of(DispatchStatus.PACKAGING)), cutoff.capture(), anyInt());
        long packagingAgeMs = afterSweep - cutoff.getValue();
        assertTrue(packagingAgeMs >= 5 * 60_000L,
                "normal packaging must receive at least a five-minute lease, age=" + packagingAgeMs);
        verify(dispatchService).returnPackagingToPending(102L, 5L);
        verify(dispatchService, never()).onTimeout(102L, 5L);
        verify(redisManager).releaseLock(eq("dispatch:compensation:lock"), anyString());
    }

    @Test
    void returnsUnacknowledgedDispatchToPendingInsteadOfTimingOutTheWorkitem() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        DispatchDO unacknowledged = row(6L, 103L, 206L, 306L, DispatchStatus.DISPATCHED);
        unacknowledged.setExecutorId(9L);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.DISPATCHED)), anyLong(), anyInt()))
                .thenReturn(List.of(unacknowledged));

        task.sweep();

        verify(dispatchService).onBusy(103L, 9L, 6L);
        verify(dispatchService, never()).onTimeout(103L, 6L);
    }

    @Test
    void marksStalePausingDispatchAsFailedSoItCanBeRecovered() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        DispatchDO pausing = row(7L, 104L, 207L, 307L, DispatchStatus.PAUSING);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED)), anyLong(), anyInt()))
                .thenReturn(List.of(pausing));

        task.sweep();

        verify(pauseService).expireTimedOutPause(eq(pausing), anyLong());
    }

    @Test
    void releasesWaitingReworkWhenTimedOutPauseBelongsToOfflineExecutor() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        DispatchDO pausing = row(8L, 104L, 207L, 307L, DispatchStatus.PAUSE_FAILED);
        pausing.setExecutorId(9L);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED)), anyLong(), anyInt()))
                .thenReturn(List.of(pausing));
        when(dispatchService.cancelPauseFailedIfExecutorReleased(104L, 8L)).thenReturn(true);

        task.sweep();

        verify(interactionWorkflowService).onPaused(104L, 8L);
        verify(pauseService, never()).expireTimedOutPause(eq(pausing), anyLong());
    }

    @Test
    void keepsWaitingReworkBlockedWhenTimedOutPauseExecutorIsStillOnline() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        DispatchDO pausing = row(9L, 104L, 207L, 307L, DispatchStatus.PAUSING);
        pausing.setExecutorId(10L);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED)), anyLong(), anyInt()))
                .thenReturn(List.of(pausing));
        when(pauseService.expireTimedOutPause(eq(pausing), anyLong())).thenReturn(true);
        when(dispatchService.cancelPauseFailedIfExecutorReleased(104L, 9L)).thenReturn(false);

        task.sweep();

        verify(interactionWorkflowService, never()).onPaused(anyLong(), anyLong());
    }

    @Test
    void oneRowFailureDoesNotAbortSweep() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PENDING)), anyLong(), anyInt()))
                .thenReturn(List.of(
                        row(1L, 100L, 200L, 300L, DispatchStatus.PENDING),
                        row(3L, 100L, 202L, 302L, DispatchStatus.PENDING)));
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED,
                DispatchStatus.RUNNING)), anyLong(), anyInt())).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(dispatchService).runPending(1L);

        task.sweep();

        verify(dispatchService).runPending(1L);
        verify(dispatchService).runPending(3L);
        verify(redisManager).releaseLock(eq("dispatch:compensation:lock"), anyString());
    }
}
