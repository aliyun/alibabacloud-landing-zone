package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.workitem.DeliveryRestartStore;
import com.aliyun.autowonder.workitem.WorkitemDeliveryRestartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Executes the durable stop-then-start sequence of a delivery restart: registers the
 * durable cancel intent against old formal executions, then enqueues the new formal
 * round idempotent on the restart round key. Late results of the stopped round stay
 * fenced by the existing dispatch_recovery cancel_requested protocol.
 *
 * <p>When an old formal execution's stop is not confirmed synchronously (executor-owned
 * rows only reach a terminal state after the executor acknowledges the pause), the
 * round persists its STOPPING_OLD waiting state and returns; the stop confirmation
 * ({@link DispatchRecoveryService#onStopped}) and the compensation sweep resume the
 * round from its persisted start intent. The new round never runs in parallel with an
 * unconfirmed old execution.
 */
@Component
public class DeliveryRestartOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRestartOrchestrator.class);
    private static final long SYSTEM_USER_ID = 0L;

    private final DispatchService dispatchService;
    private final DispatchRecoveryService recoveryService;
    private final DeliveryRestartProgressStore progressStore;
    private final DeliveryRestartStore restartStore;

    public DeliveryRestartOrchestrator(DispatchService dispatchService,
            DispatchRecoveryService recoveryService, DeliveryRestartProgressStore progressStore,
            DeliveryRestartStore restartStore) {
        this.dispatchService = dispatchService;
        this.recoveryService = recoveryService;
        this.progressStore = progressStore;
        this.restartStore = restartStore;
    }

    // AFTER_COMMIT like DispatchAssignmentListener: the restart must not stop old
    // executions or enqueue the new round while the assignment transaction can still
    // roll back.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryRestarted(WorkitemDeliveryRestartedEvent e) {
        if (e.getAgentId() == null || e.getSdlcStepId() == null) {
            log.info("delivery restart skipped workitemId={} (no sdlcStep or agent)", e.getWorkitemId());
            return;
        }
        try {
            restart(e.getTenantId(), e.getWorkitemId(), e.getSdlcStepId(), e.getAgentId(),
                    e.getUserId(), e.getIdempotencyKey());
        } catch (RuntimeException failure) {
            log.error("delivery restart failed workitemId={} key={}", e.getWorkitemId(),
                    e.getIdempotencyKey(), failure);
            progressStore.markFailed(e.getTenantId(), e.getWorkitemId(), e.getIdempotencyKey(),
                    rootMessage(failure));
        }
    }

    /** Synchronous core shared by tests and the async listener entry. */
    public void restart(long tenantId, long workitemId, Long sdlcStepId, long agentId,
            long userId, String idempotencyKey) {
        DispatchDO existing = dispatchService.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) {
            log.info("delivery restart idempotent hit workitemId={} key={} dispatchId={}",
                    workitemId, idempotencyKey, existing.getId());
            return;
        }
        List<DispatchDO> oldFormal = nonTerminalFormal(tenantId, workitemId);
        if (!oldFormal.isEmpty()) {
            progressStore.markStoppingOld(tenantId, workitemId, idempotencyKey, oldFormal.size());
            for (DispatchDO old : oldFormal) {
                // Durable intent only: the stop protocol keeps stop_pending so the executor
                // isolation guard stays armed; the sweep retries the delivery until ack.
                recoveryService.requestRestartStop(tenantId, workitemId, old.getId(), userId);
            }
            // Stop requests for pre-delivery rows resolve synchronously (straight to
            // CANCELED); executor-owned rows stay PAUSING until the stop is confirmed.
            if (!nonTerminalFormal(tenantId, workitemId).isEmpty()) {
                log.info("delivery restart waits for old execution stop workitemId={} key={}",
                        workitemId, idempotencyKey);
                return;
            }
        }
        startRound(tenantId, workitemId, sdlcStepId, agentId, userId, idempotencyKey);
    }

    /** Advances the workitem's waiting rounds once every old formal execution has stopped. */
    public void advanceWaitingRestarts(long tenantId, long workitemId) {
        for (DeliveryRestartStore.WaitingRestart round : restartStore.waitingRestarts(tenantId, workitemId)) {
            advance(round);
        }
    }

    /** Sweep over all workitems: resumes waiting rounds after a restart of this service. */
    public void sweepWaitingRestarts() {
        for (DeliveryRestartStore.WaitingRestart round : restartStore.waitingRestarts(100)) {
            advance(round);
        }
    }

    private void advance(DeliveryRestartStore.WaitingRestart round) {
        if (round.sdlcStepId() == null || round.agentId() == null) {
            log.warn("waiting restart round without start intent skipped workitemId={} round={}",
                    round.workitemId(), round.restartRound());
            return;
        }
        String key = DeliveryRestartStore.restartIdempotencyKey(round.workitemId(), round.restartRound());
        try {
            if (!nonTerminalFormal(round.tenantId(), round.workitemId()).isEmpty()) {
                return;
            }
            startRound(round.tenantId(), round.workitemId(), round.sdlcStepId(), round.agentId(),
                    round.requestedBy(), key);
        } catch (RuntimeException failure) {
            log.error("delivery restart advancement failed workitemId={} key={}", round.workitemId(), key, failure);
            progressStore.markFailed(round.tenantId(), round.workitemId(), key, rootMessage(failure));
        }
    }

    private void startRound(long tenantId, long workitemId, Long sdlcStepId, long agentId,
            long userId, String idempotencyKey) {
        DispatchDO existing = dispatchService.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) {
            // A concurrent trigger (stop confirmation + sweep) already started this round.
            progressStore.markStarted(tenantId, workitemId, idempotencyKey, existing.getId());
            return;
        }
        progressStore.markStarting(tenantId, workitemId, idempotencyKey);
        DispatchDO next = dispatchService.enqueueRestartAssignment(tenantId, workitemId,
                sdlcStepId, agentId, idempotencyKey, userId == 0 ? SYSTEM_USER_ID : userId);
        progressStore.markStarted(tenantId, workitemId, idempotencyKey, next.getId());
        dispatchService.runPending(next.getId());
    }

    private List<DispatchDO> nonTerminalFormal(long tenantId, long workitemId) {
        return dispatchService.listByWorkitem(tenantId, workitemId).stream()
                .filter(d -> !dispatchService.isInteractionDispatch(d))
                .filter(this::needsStop)
                .toList();
    }

    private boolean needsStop(DispatchDO d) {
        if (DispatchStatus.isTerminal(d.getStatus())) {
            return false;
        }
        // PENDING/PACKAGING formal rows that never reached an executor are fenced by the
        // cancel intent alone; PAUSED/PAUSING rows are already stopped or stopping.
        return true;
    }

    private static String rootMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
