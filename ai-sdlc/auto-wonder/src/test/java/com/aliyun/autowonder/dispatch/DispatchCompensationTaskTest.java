package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchCompensationTaskTest {

    private DispatchDao dispatchDao;
    private DispatchService dispatchService;
    private DispatchPauseService pauseService;
    private RedisManager redisManager;
    private InteractionWorkflowService interactionWorkflowService;
    private DispatchCompensationTask task;
    private com.aliyun.autowonder.executor.ExecutorRegistry registry;
    private DispatchRuntimeReconciler reconciler;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        dispatchService = mock(DispatchService.class);
        pauseService = mock(DispatchPauseService.class);
        redisManager = mock(RedisManager.class);
        interactionWorkflowService = mock(InteractionWorkflowService.class);
        task = new DispatchCompensationTask(dispatchDao, dispatchService, pauseService,
                interactionWorkflowService, redisManager);
        registry = mock(com.aliyun.autowonder.executor.ExecutorRegistry.class);
        reconciler = mock(DispatchRuntimeReconciler.class);
        task.setRuntimeReconciliation(registry, reconciler);
    }

    private DispatchDO row(long id, long tenantId, long workitemId, long stepId, String status) {
        DispatchDO d = new DispatchDO();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setWorkitemId(workitemId);
        d.setSdlcStepId(stepId);
        d.setStatus(status);
        d.setExecutorId(9L);
        d.setAgentId(20L);
        d.setGmtModified(new java.util.Date(System.currentTimeMillis() - 2 * 60 * 60_000L));
        return d;
    }

    @Test
    void requestReconciliationFailureDoesNotSkipCheckpointRetentionOrRelease() {
        var requests = mock(DispatchRequestService.class);
        var checkpoints = mock(DispatchCheckpointService.class);
        task.setDispatchRequests(requests);
        task.setCheckpoints(checkpoints);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        doThrow(new IllegalStateException("request reconciliation failed")).when(requests).reconcile();

        assertDoesNotThrow(task::sweep);

        verify(requests).reconcile();
        verify(checkpoints).reconcileRetention();
        verify(interactionWorkflowService).reconcileReleasedWaiters();
        verify(redisManager).releaseLock(eq("dispatch:compensation:lock"), anyString());
    }

    @Test
    void skipsWhenLockNotAcquired() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        task.sweep();
        verify(dispatchDao, never()).listStuck(anyList(), anyLong(), anyInt());
    }

    @Test
    void redrivesStuckPendingAndPreservesRunningWithoutAuthoritativeInventory() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PENDING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(1L, 100L, 200L, 300L, DispatchStatus.PENDING)));
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED,
                DispatchStatus.RUNNING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(2L, 101L, 201L, 301L, DispatchStatus.RUNNING)));

        task.sweep();

        verify(dispatchService).runPending(1L);
        verify(dispatchService, never()).onTimeout(anyLong(), anyLong());
        verify(dispatchService, never()).runPending(2L);
        verify(redisManager).releaseLock(eq("dispatch:compensation:lock"), anyString());
    }

    @Test
    void scheduledRunRetainsExistingTimeoutCompensation() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        var scheduled = row(2L, 101L, 201L, 301L, DispatchStatus.RUNNING);
        scheduled.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED, DispatchStatus.RUNNING)), anyLong(), anyInt()))
                .thenReturn(List.of(scheduled));
        task.sweep();
        verify(dispatchService).onTimeout(101L, 2L);
        verifyNoInteractions(reconciler);
    }

    @Test
    void reconcilesRequestsAndTwoHourOldExecutionWithoutTimingOutOrDuplicatingIt() {
        var requests = mock(DispatchRequestService.class);
        task.setDispatchRequests(requests);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        var old = row(2L, 101L, 201L, 301L, DispatchStatus.RUNNING);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED, DispatchStatus.RUNNING)), anyLong(), anyInt()))
                .thenReturn(List.of(old));
        // Ownership includes a local RECOVERING task even when no provider call is running yet.
        when(registry.currentDispatchSnapshot(9L)).thenReturn(java.util.Optional.of(
                new com.aliyun.autowonder.executor.ExecutorDispatchSnapshot("session", 10, true, true,
                        java.util.Set.of(), java.util.Set.of(2L), java.util.Set.of(), java.util.Set.of(),
                        null, System.currentTimeMillis())));
        task.sweep();
        verify(requests).reconcile();
        verify(reconciler).request(101L, 9L, 20L, "session");
        verifyNoInteractions(dispatchService);
        org.junit.jupiter.api.Assertions.assertEquals(DispatchStatus.RUNNING, old.getStatus());
    }

    @Test
    void incompleteInventoryCannotAuthorizeRecoveryAfterLongDowntime() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED, DispatchStatus.RUNNING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(2L, 101L, 201L, 301L, DispatchStatus.ACKED)));
        when(registry.currentDispatchSnapshot(9L)).thenReturn(java.util.Optional.of(
                new com.aliyun.autowonder.executor.ExecutorDispatchSnapshot("session", 10, true, false,
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                        null, System.currentTimeMillis())));
        task.sweep();
        verifyNoInteractions(reconciler, dispatchService);
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

    @Test
    void onePhaseQueryFailureDoesNotAbortLaterPhases() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.PENDING)), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("pending query failed"));
        when(dispatchDao.listStuck(eq(List.of(DispatchStatus.ACKED,
                DispatchStatus.RUNNING)), anyLong(), anyInt()))
                .thenReturn(List.of(row(12L, 101L, 201L, 301L, DispatchStatus.RUNNING)));

        assertDoesNotThrow(task::sweep);

        verify(dispatchDao).listStuck(eq(List.of(DispatchStatus.ACKED, DispatchStatus.RUNNING)), anyLong(), anyInt());
        verify(dispatchService, never()).onTimeout(anyLong(), anyLong());
        verify(redisManager).releaseLock(eq("dispatch:compensation:lock"), anyString());
    }

    @Test
    void repairsReleasedWaitersEvenWhenThereAreNoStalePausingRows() {
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);

        task.sweep();

        verify(interactionWorkflowService).reconcileReleasedWaiters();
    }

}
