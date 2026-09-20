package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.configuration.ThreadPoolManager;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.websocket.DispatchDrainScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Repairs lost stop/pause receipts only from a complete, current Runtime inventory. */
@Service
public class DispatchRuntimeReconciler {
    private static final Logger log = LoggerFactory.getLogger(DispatchRuntimeReconciler.class);
    private static final int BATCH = 200;
    private static final long PAUSE_RECEIPT_GRACE_MS = 120_000L;
    private static final List<String> PAUSE_STATES =
            List.of(DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED);

    private final ExecutorRegistry registry;
    private final DispatchDao dispatchDao;
    private final DispatchRecoveryService recovery;
    private final DispatchPauseService pauseService;
    private final DispatchService dispatchService;
    private final InteractionWorkflowService workflowService;
    private final DispatchDrainScheduler drainScheduler;
    private final Executor executor;
    private final ConcurrentHashMap<Long, Request> pending = new ConcurrentHashMap<>();
    private final java.util.Set<Long> scheduled = ConcurrentHashMap.newKeySet();

    @Autowired
    public DispatchRuntimeReconciler(ExecutorRegistry registry, DispatchDao dispatchDao,
            DispatchRecoveryService recovery, DispatchPauseService pauseService,
            DispatchService dispatchService, InteractionWorkflowService workflowService,
            DispatchDrainScheduler drainScheduler) {
        this(registry, dispatchDao, recovery, pauseService, dispatchService, workflowService,
                drainScheduler, ThreadPoolManager.asyncTaskThreadPool);
    }

    DispatchRuntimeReconciler(ExecutorRegistry registry, DispatchDao dispatchDao,
            DispatchRecoveryService recovery, DispatchPauseService pauseService,
            DispatchService dispatchService, InteractionWorkflowService workflowService,
            DispatchDrainScheduler drainScheduler, Executor executor) {
        this.registry = registry;
        this.dispatchDao = dispatchDao;
        this.recovery = recovery;
        this.pauseService = pauseService;
        this.dispatchService = dispatchService;
        this.workflowService = workflowService;
        this.drainScheduler = drainScheduler;
        this.executor = executor;
    }

    public void request(long tenantId, long executorId, long agentId, String sessionId) {
        pending.put(executorId, new Request(tenantId, executorId, agentId, sessionId));
        schedule(executorId);
    }

    private void schedule(long executorId) {
        if (!scheduled.add(executorId)) return;
        try {
            executor.execute(() -> {
                try {
                    Request request;
                    while ((request = pending.remove(executorId)) != null) {
                        reconcileNow(request.tenantId(), request.executorId(), request.agentId(),
                                request.sessionId());
                    }
                } catch (RuntimeException e) {
                    log.warn("runtime inventory reconciliation failed executorId={}", executorId, e);
                } finally {
                    scheduled.remove(executorId);
                    if (pending.containsKey(executorId)) schedule(executorId);
                }
            });
        } catch (RejectedExecutionException e) {
            scheduled.remove(executorId);
            log.warn("runtime inventory reconciliation rejected executorId={}", executorId, e);
        }
    }

    void reconcileNow(long tenantId, long executorId, long agentId, String expectedSessionId) {
        ExecutorDispatchSnapshot snapshot = currentComplete(executorId, expectedSessionId);
        if (snapshot == null) return;
        try {
            recovery.reconcileExecutor(executorId);
        } catch (RuntimeException e) {
            log.warn("runtime stop reconciliation failed executorId={}", executorId, e);
        }
        try {
            reconcilePauses(executorId, expectedSessionId);
        } catch (RuntimeException e) {
            log.warn("runtime pause query failed executorId={}", executorId, e);
        }
        if (currentComplete(executorId, expectedSessionId) == null) return;
        try {
            workflowService.reconcileReleasedWaiters(tenantId, executorId);
        } catch (RuntimeException e) {
            log.warn("runtime waiter reconciliation failed executorId={}", executorId, e);
        }
        if (currentComplete(executorId, expectedSessionId) == null) return;
        try {
            recovery.wakeCapacityWaits(agentId);
        } catch (RuntimeException e) {
            log.warn("runtime capacity wake failed executorId={}", executorId, e);
        }
        // Repair failures must not suppress scheduling independent queued tasks.
        if (currentComplete(executorId, expectedSessionId) != null) {
            drainScheduler.request(agentId);
        }
    }

    private void reconcilePauses(long executorId, String expectedSessionId) {
        for (DispatchDO row : safe(dispatchDao.listByExecutorAndStatuses(
                executorId, PAUSE_STATES, BATCH))) {
            try {
                ExecutorDispatchSnapshot current = currentComplete(executorId, expectedSessionId);
                if (current == null) return;
                if (current.ownedDispatchIds().contains(row.getId())) continue;
                if (DispatchStatus.PAUSING.equals(row.getStatus())) {
                    pauseService.expireTimedOutPause(row,
                            System.currentTimeMillis() - PAUSE_RECEIPT_GRACE_MS);
                }
                if (dispatchService.cancelPauseFailedIfExecutorReleased(
                        row.getTenantId(), row.getId())) {
                    workflowService.onPaused(row.getTenantId(), row.getId());
                }
            } catch (RuntimeException e) {
                log.warn("runtime pause reconciliation failed dispatchId={} executorId={}",
                        row.getId(), executorId, e);
            }
        }
    }

    private ExecutorDispatchSnapshot currentComplete(long executorId, String expectedSessionId) {
        return registry.currentDispatchSnapshot(executorId)
                .filter(value -> value.inventoryReady() && value.inventoryError() == null)
                .filter(value -> java.util.Objects.equals(value.sessionId(), expectedSessionId))
                .orElse(null);
    }

    private static List<DispatchDO> safe(List<DispatchDO> rows) {
        return rows == null ? List.of() : rows;
    }

    private record Request(long tenantId, long executorId, long agentId, String sessionId) {}
}
