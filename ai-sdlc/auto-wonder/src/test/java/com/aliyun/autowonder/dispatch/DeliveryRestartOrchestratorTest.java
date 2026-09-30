package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.workitem.DeliveryRestartStore;
import com.aliyun.autowonder.workitem.WorkitemDeliveryRestartedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryRestartOrchestratorTest {

    static final String KEY = "restart:1:500";

    DispatchService dispatchService;
    DispatchRecoveryService recoveryService;
    DeliveryRestartProgressStore progressStore;
    DeliveryRestartStore restartStore;
    DeliveryRestartOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        recoveryService = mock(DispatchRecoveryService.class);
        progressStore = mock(DeliveryRestartProgressStore.class);
        restartStore = mock(DeliveryRestartStore.class);
        orchestrator = new DeliveryRestartOrchestrator(dispatchService, recoveryService,
                progressStore, restartStore);
        when(dispatchService.findByIdempotencyKey(100L, KEY)).thenReturn(null);
        when(dispatchService.listByWorkitem(100L, 500L)).thenReturn(List.of());
        when(dispatchService.isInteractionDispatch(any())).thenCallRealMethod();
    }

    private DispatchDO dispatch(long id, String status) {
        DispatchDO d = new DispatchDO();
        d.setId(id);
        d.setTenantId(100L);
        d.setWorkitemId(500L);
        d.setStatus(status);
        return d;
    }

    private void enqueueReturns(long dispatchId) {
        DispatchDO next = dispatch(dispatchId, "PENDING");
        when(dispatchService.enqueueRestartAssignment(eq(100L), eq(500L), eq(300031L), eq(40013L),
                eq(KEY), anyLong())).thenReturn(next);
    }

    @Test
    void idempotentHitReturnsWithoutStoppingOrEnqueueing() {
        when(dispatchService.findByIdempotencyKey(100L, KEY)).thenReturn(dispatch(88L, "PENDING"));

        orchestrator.restart(100L, 500L, 300031L, 40013L, 7L, KEY);

        verify(dispatchService, never()).enqueueRestartAssignment(anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
        verifyNoInteractions(recoveryService, progressStore);
    }

    @Test
    void waitsForStopConfirmationWhenOldExecutionStillStopping() {
        DispatchDO old = dispatch(101L, "RUNNING");
        when(dispatchService.listByWorkitem(100L, 500L)).thenReturn(List.of(old));

        orchestrator.restart(100L, 500L, 300031L, 40013L, 7L, KEY);

        verify(progressStore).markStoppingOld(100L, 500L, KEY, 1);
        verify(recoveryService).requestRestartStop(100L, 500L, 101L, 7L);
        // The stop is not confirmed yet: the round must keep waiting instead of running
        // the new dispatch in parallel with the unconfirmed old execution.
        verify(progressStore, never()).markStarting(anyLong(), anyLong(), anyString());
        verify(dispatchService, never()).enqueueRestartAssignment(anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
    }

    @Test
    void stopsEveryNonTerminalOldFormalDispatchBeforeWaiting() {
        String[] statuses = {"PENDING", "PACKAGING", "DISPATCHED", "ACKED", "RUNNING",
                "PAUSING", "PAUSED", "PAUSE_FAILED", "WAITING_FOR_PAUSE"};
        List<DispatchDO> old = new java.util.ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            old.add(dispatch(101L + i, statuses[i]));
        }
        when(dispatchService.listByWorkitem(100L, 500L)).thenReturn(old);

        orchestrator.restart(100L, 500L, 300031L, 40013L, 7L, KEY);

        verify(progressStore).markStoppingOld(100L, 500L, KEY, old.size());
        for (DispatchDO d : old) {
            verify(recoveryService).requestRestartStop(100L, 500L, d.getId(), 7L);
        }
        verify(dispatchService, never()).runPending(anyLong());
    }

    @Test
    void startsImmediatelyWhenStopRequestsResolveSynchronously() {
        // Pre-delivery rows go straight to CANCELED by the stop request itself, so the
        // re-check after the requests sees only terminal rows.
        when(dispatchService.listByWorkitem(100L, 500L))
                .thenReturn(List.of(dispatch(101L, "PENDING")))
                .thenReturn(List.of(dispatch(101L, "CANCELED")));
        enqueueReturns(88L);

        orchestrator.restart(100L, 500L, 300031L, 40013L, 7L, KEY);

        InOrder inOrder = inOrder(progressStore, recoveryService, dispatchService);
        inOrder.verify(progressStore).markStoppingOld(100L, 500L, KEY, 1);
        inOrder.verify(recoveryService).requestRestartStop(100L, 500L, 101L, 7L);
        inOrder.verify(progressStore).markStarting(100L, 500L, KEY);
        inOrder.verify(dispatchService).enqueueRestartAssignment(100L, 500L, 300031L, 40013L, KEY, 7L);
        inOrder.verify(progressStore).markStarted(100L, 500L, KEY, 88L);
        inOrder.verify(dispatchService).runPending(88L);
    }

    @Test
    void terminalOldDispatchesDoNotBlockTheNewRound() {
        List<DispatchDO> old = Stream.of("SUCCEEDED", "FAILED", "TIMEOUT", "CANCELED")
                .map(status -> dispatch(101L, status)).toList();
        when(dispatchService.listByWorkitem(100L, 500L)).thenReturn(old);
        enqueueReturns(88L);

        orchestrator.restart(100L, 500L, 300031L, 40013L, 7L, KEY);

        verify(progressStore, never()).markStoppingOld(anyLong(), anyLong(), anyString(), anyInt());
        verifyNoInteractions(recoveryService);
        verify(dispatchService).enqueueRestartAssignment(100L, 500L, 300031L, 40013L, KEY, 7L);
        verify(dispatchService).runPending(88L);
    }

    @Test
    void interactionDispatchesAreExcludedFromStop() {
        List<DispatchDO> old = Stream.of("COMMENT_INTERACTION", "SIDE_INTERACTION", "CANONICAL_INTERACTION")
                .map(mode -> {
                    DispatchDO d = dispatch(101L, "RUNNING");
                    d.setResumeMode(mode);
                    return d;
                }).toList();
        when(dispatchService.listByWorkitem(100L, 500L)).thenReturn(old);
        enqueueReturns(88L);

        orchestrator.restart(100L, 500L, 300031L, 40013L, 7L, KEY);

        verifyNoInteractions(recoveryService);
        verify(progressStore, never()).markStoppingOld(anyLong(), anyLong(), anyString(), anyInt());
        verify(dispatchService).runPending(88L);
    }

    @Test
    void systemUserFallbackWhenUserIdUnknown() {
        enqueueReturns(88L);

        orchestrator.restart(100L, 500L, 300031L, 40013L, 0L, KEY);

        verify(dispatchService).enqueueRestartAssignment(100L, 500L, 300031L, 40013L, KEY, 0L);
    }

    @Test
    void advanceWaitingRestartsStartsRoundFromPersistedIntent() {
        DeliveryRestartStore.WaitingRestart round = new DeliveryRestartStore.WaitingRestart(
                100L, 500L, 2, 300035L, 40014L, 7L);
        when(restartStore.waitingRestarts(100L, 500L)).thenReturn(List.of(round));
        when(dispatchService.findByIdempotencyKey(100L, "restart:2:500")).thenReturn(null);
        when(dispatchService.listByWorkitem(100L, 500L))
                .thenReturn(List.of(dispatch(101L, "CANCELED")));
        DispatchDO next = dispatch(88L, "PENDING");
        when(dispatchService.enqueueRestartAssignment(100L, 500L, 300035L, 40014L, "restart:2:500", 7L))
                .thenReturn(next);

        orchestrator.advanceWaitingRestarts(100L, 500L);

        InOrder inOrder = inOrder(progressStore, dispatchService);
        inOrder.verify(progressStore).markStarting(100L, 500L, "restart:2:500");
        inOrder.verify(dispatchService).enqueueRestartAssignment(100L, 500L, 300035L, 40014L,
                "restart:2:500", 7L);
        inOrder.verify(progressStore).markStarted(100L, 500L, "restart:2:500", 88L);
        inOrder.verify(dispatchService).runPending(88L);
    }

    @Test
    void advanceWaitingRestartsKeepsWaitingWhileOldExecutionStillNonTerminal() {
        DeliveryRestartStore.WaitingRestart round = new DeliveryRestartStore.WaitingRestart(
                100L, 500L, 2, 300035L, 40014L, 7L);
        when(restartStore.waitingRestarts(100L, 500L)).thenReturn(List.of(round));
        when(dispatchService.listByWorkitem(100L, 500L))
                .thenReturn(List.of(dispatch(101L, "PAUSING")));

        orchestrator.advanceWaitingRestarts(100L, 500L);

        verify(dispatchService, never()).enqueueRestartAssignment(anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
    }

    @Test
    void advanceWaitingRestartsConvergesWhenRoundAlreadyStartedByConcurrentTrigger() {
        DeliveryRestartStore.WaitingRestart round = new DeliveryRestartStore.WaitingRestart(
                100L, 500L, 2, 300035L, 40014L, 7L);
        when(restartStore.waitingRestarts(100L, 500L)).thenReturn(List.of(round));
        when(dispatchService.listByWorkitem(100L, 500L))
                .thenReturn(List.of(dispatch(101L, "CANCELED")));
        when(dispatchService.findByIdempotencyKey(100L, "restart:2:500")).thenReturn(dispatch(99L, "RUNNING"));

        orchestrator.advanceWaitingRestarts(100L, 500L);

        verify(progressStore).markStarted(100L, 500L, "restart:2:500", 99L);
        verify(dispatchService, never()).enqueueRestartAssignment(anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
    }

    @Test
    void advanceSkipsWaitingRoundsWithoutPersistedIntent() {
        DeliveryRestartStore.WaitingRestart legacy = new DeliveryRestartStore.WaitingRestart(
                100L, 500L, 1, null, null, 7L);
        when(restartStore.waitingRestarts(100L, 500L)).thenReturn(List.of(legacy));

        orchestrator.advanceWaitingRestarts(100L, 500L);

        verifyNoInteractions(recoveryService);
        verify(dispatchService, never()).enqueueRestartAssignment(anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyLong());
    }

    @Test
    void sweepWaitingRestartsAdvancesEveryWorkitemsWaitingRound() {
        DeliveryRestartStore.WaitingRestart otherWorkitem = new DeliveryRestartStore.WaitingRestart(
                100L, 501L, 1, 300031L, 40013L, 7L);
        when(restartStore.waitingRestarts(100)).thenReturn(List.of(otherWorkitem));
        when(dispatchService.listByWorkitem(100L, 501L)).thenReturn(List.of());
        when(dispatchService.findByIdempotencyKey(100L, "restart:1:501")).thenReturn(null);
        DispatchDO next = dispatch(77L, "PENDING");
        next.setWorkitemId(501L);
        when(dispatchService.enqueueRestartAssignment(100L, 501L, 300031L, 40013L, "restart:1:501", 7L))
                .thenReturn(next);

        orchestrator.sweepWaitingRestarts();

        verify(dispatchService).enqueueRestartAssignment(100L, 501L, 300031L, 40013L, "restart:1:501", 7L);
        verify(dispatchService).runPending(77L);
    }

    @Test
    void advancementFailureMarksRoundFailedWithRootMessage() {
        DeliveryRestartStore.WaitingRestart round = new DeliveryRestartStore.WaitingRestart(
                100L, 500L, 2, 300035L, 40014L, 7L);
        when(restartStore.waitingRestarts(100L, 500L)).thenReturn(List.of(round));
        when(dispatchService.listByWorkitem(100L, 500L)).thenReturn(List.of());
        when(dispatchService.findByIdempotencyKey(100L, "restart:2:500")).thenReturn(null);
        when(dispatchService.enqueueRestartAssignment(anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong())).thenThrow(new RuntimeException("executor offline"));

        orchestrator.advanceWaitingRestarts(100L, 500L);

        verify(progressStore).markFailed(100L, 500L, "restart:2:500", "executor offline");
        verify(dispatchService, never()).runPending(anyLong());
    }

    @Test
    void restartFailureMarksRoundFailedWithRootMessage() {
        when(dispatchService.enqueueRestartAssignment(anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong())).thenThrow(new RuntimeException("executor offline"));

        orchestrator.onDeliveryRestarted(new WorkitemDeliveryRestartedEvent(
                100L, 500L, 300031L, 40013L, 7L, KEY, null));

        verify(progressStore).markFailed(100L, 500L, KEY, "executor offline");
        verify(progressStore, never()).markStarted(anyLong(), anyLong(), anyString(), anyLong());
        verify(dispatchService, never()).runPending(anyLong());
    }

    @Test
    void failureWithoutMessageFallsBackToExceptionSimpleName() {
        when(dispatchService.enqueueRestartAssignment(anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong())).thenThrow(new IllegalStateException());

        orchestrator.onDeliveryRestarted(new WorkitemDeliveryRestartedEvent(
                100L, 500L, 300031L, 40013L, 7L, KEY, null));

        verify(progressStore).markFailed(100L, 500L, KEY, "IllegalStateException");
    }

    @Test
    void listenerSkipsWhenAgentOrStepMissing() {
        orchestrator.onDeliveryRestarted(new WorkitemDeliveryRestartedEvent(
                100L, 500L, 300031L, null, 7L, KEY, null));
        orchestrator.onDeliveryRestarted(new WorkitemDeliveryRestartedEvent(
                100L, 500L, null, 40013L, 7L, KEY, null));

        verifyNoInteractions(dispatchService, recoveryService, progressStore);
    }
}
