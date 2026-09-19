package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.websocket.DispatchDrainScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DispatchRuntimeReconcilerTest {

    @Test
    void stopReconciliationFailureDoesNotBlockSchedulingOtherTasks() {
        Fixture f = new Fixture(snapshot("s1", true, Set.of()));
        doThrow(new RuntimeException("stop query unavailable"))
                .when(f.recovery).reconcileExecutor(10L);

        f.reconciler.reconcileNow(100L, 10L, 20L, "s1");

        verify(f.drainScheduler).request(20L);
    }

    @Test
    void pauseQueryFailureDoesNotBlockSchedulingOtherTasks() {
        Fixture f = new Fixture(snapshot("s1", true, Set.of()));
        when(f.dispatchDao.listByExecutorAndStatuses(anyLong(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("pause query unavailable"));

        f.reconciler.reconcileNow(100L, 10L, 20L, "s1");

        verify(f.drainScheduler).request(20L);
    }

    @Test
    void waiterReconciliationFailureDoesNotBlockSchedulingOtherTasks() {
        Fixture f = new Fixture(snapshot("s1", true, Set.of()));
        doThrow(new RuntimeException("waiter query unavailable"))
                .when(f.workflowService).reconcileReleasedWaiters(100L, 10L);

        f.reconciler.reconcileNow(100L, 10L, 20L, "s1");

        verify(f.recovery).wakeCapacityWaits(20L);
        verify(f.drainScheduler).request(20L);
    }

    @Test
    void sessionReplacementDuringRepairDoesNotDrain() {
        Fixture f = new Fixture(snapshot("s1", true, Set.of()));
        doAnswer(invocation -> {
            when(f.registry.currentDispatchSnapshot(10L))
                    .thenReturn(Optional.of(snapshot("s2", true, Set.of())));
            return null;
        }).when(f.recovery).reconcileExecutor(10L);

        f.reconciler.reconcileNow(100L, 10L, 20L, "s1");

        verifyNoInteractions(f.drainScheduler);
        verify(f.recovery, never()).wakeCapacityWaits(anyLong());
    }

    @Test
    void incompleteInventoryDoesNotReconcileAnything() {
        Fixture f = new Fixture(snapshot("s1", false, Set.of()));

        f.reconciler.reconcileNow(100L, 10L, 20L, "s1");

        verifyNoInteractions(f.recovery, f.pauseService, f.dispatchService,
                f.workflowService, f.drainScheduler);
    }

    @Test
    void completeInventoryReconcilesOnlyMissingPauseAndContinuesAfterOneBadRow() {
        Fixture f = new Fixture(snapshot("s1", true, Set.of(2L)));
        DispatchDO missingOne = row(1L, DispatchStatus.PAUSING);
        DispatchDO owned = row(2L, DispatchStatus.PAUSE_FAILED);
        DispatchDO missingTwo = row(3L, DispatchStatus.PAUSE_FAILED);
        when(f.dispatchDao.listByExecutorAndStatuses(10L,
                List.of(DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED), 200))
                .thenReturn(List.of(missingOne, owned, missingTwo));
        when(f.pauseService.expireTimedOutPause(eq(missingOne), anyLong()))
                .thenThrow(new RuntimeException("one broken row"));
        when(f.dispatchService.cancelPauseFailedIfExecutorReleased(100L, 3L)).thenReturn(true);

        f.reconciler.reconcileNow(100L, 10L, 20L, "s1");

        verify(f.recovery).reconcileExecutor(10L);
        verify(f.dispatchService, never()).cancelPauseFailedIfExecutorReleased(100L, 2L);
        verify(f.dispatchService).cancelPauseFailedIfExecutorReleased(100L, 3L);
        verify(f.workflowService).onPaused(100L, 3L);
        verify(f.workflowService).reconcileReleasedWaiters(100L, 10L);
        verify(f.drainScheduler).request(20L);
    }

    @Test
    void replacedSessionAbortsReconciliation() {
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        when(registry.currentDispatchSnapshot(10L)).thenReturn(
                Optional.of(snapshot("new-session", true, Set.of())));
        Fixture f = new Fixture(registry);

        f.reconciler.reconcileNow(100L, 10L, 20L, "old-session");

        verifyNoInteractions(f.recovery, f.pauseService, f.dispatchService,
                f.workflowService, f.drainScheduler);
    }

    private static ExecutorDispatchSnapshot snapshot(String session, boolean ready, Set<Long> owned) {
        return new ExecutorDispatchSnapshot(session, 10, true, ready, Set.of(), owned,
                Set.of(), Set.of(), null, 1L);
    }

    private static DispatchDO row(long id, String status) {
        DispatchDO row = new DispatchDO();
        row.setId(id);
        row.setTenantId(100L);
        row.setExecutorId(10L);
        row.setStatus(status);
        return row;
    }

    private static final class Fixture {
        final ExecutorRegistry registry;
        final DispatchDao dispatchDao = mock(DispatchDao.class);
        final DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        final DispatchPauseService pauseService = mock(DispatchPauseService.class);
        final DispatchService dispatchService = mock(DispatchService.class);
        final InteractionWorkflowService workflowService = mock(InteractionWorkflowService.class);
        final DispatchDrainScheduler drainScheduler = mock(DispatchDrainScheduler.class);
        final DispatchRuntimeReconciler reconciler;

        Fixture(ExecutorDispatchSnapshot snapshot) {
            this(mock(ExecutorRegistry.class));
            when(registry.currentDispatchSnapshot(10L)).thenReturn(Optional.of(snapshot));
        }

        Fixture(ExecutorRegistry registry) {
            this.registry = registry;
            this.reconciler = new DispatchRuntimeReconciler(registry, dispatchDao, recovery,
                    pauseService, dispatchService, workflowService, drainScheduler, Runnable::run);
        }
    }
}
