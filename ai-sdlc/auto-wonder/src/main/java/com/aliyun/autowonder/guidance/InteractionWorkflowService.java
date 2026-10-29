package com.aliyun.autowonder.guidance;

import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchFailureReason;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchRecoveryService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies a side-interaction workflow effect without letting the side session drive SDLC directly. */
@Service
public class InteractionWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(InteractionWorkflowService.class);
    private static final int RELEASE_BATCH = 200;
    private static final Set<String> PAUSEABLE = Set.of(
            DispatchStatus.PENDING, DispatchStatus.PACKAGING,
            DispatchStatus.DISPATCHED, DispatchStatus.ACKED, DispatchStatus.RUNNING,
            DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED);

    private final DispatchDao dispatchDao;
    private final DispatchService dispatchService;
    private final DispatchPauseService pauseService;
    private final AgentSdlcResolver sdlcResolver;
    private final WorkitemService workitemService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final WorkitemDao workitemDao;
    private DispatchRecoveryService recovery;

    @org.springframework.beans.factory.annotation.Autowired
    public void setRecovery(DispatchRecoveryService value) {
        this.recovery = value;
    }

    public InteractionWorkflowService(DispatchDao dispatchDao, DispatchService dispatchService,
            DispatchPauseService pauseService, AgentSdlcResolver sdlcResolver,
            WorkitemService workitemService, ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager, WorkitemDao workitemDao) {
        this.dispatchDao = dispatchDao;
        this.dispatchService = dispatchService;
        this.pauseService = pauseService;
        this.sdlcResolver = sdlcResolver;
        this.workitemService = workitemService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workitemDao = workitemDao;
    }

    /** Applies an interaction plan only when it came from the executor that owns the side dispatch. */
    public DispatchDO applyFromExecutor(long tenantId, long executorId, long sideDispatchId,
            JSONObject plan) {
        DispatchDO side = dispatchDao.findById(sideDispatchId);
        if (side == null || side.getTenantId() != tenantId
                || !Objects.equals(side.getExecutorId(), executorId)) {
            return null;
        }
        return apply(tenantId, sideDispatchId, plan);
    }

    public DispatchDO apply(long tenantId, long sideDispatchId, JSONObject plan) {
        DispatchDO side = dispatchDao.findById(sideDispatchId);
        if (side == null || side.getTenantId() != tenantId
                || (!"SIDE_INTERACTION".equals(side.getResumeMode())
                    && !"CANONICAL_INTERACTION".equals(side.getResumeMode()))
                || plan == null) {
            return null;
        }
        long targetAgentId = side.getAgentId();
        long proposedTargetAgentId = plan.getLongValue("targetAgentId");
        if (proposedTargetAgentId > 0 && proposedTargetAgentId != targetAgentId) {
            log.warn("interaction plan target ignored sideDispatchId={} proposedAgentId={} authoritativeAgentId={}",
                    sideDispatchId, proposedTargetAgentId, targetAgentId);
        }
        Long targetSdlcId = sdlcResolver.resolveSdlcId(tenantId, targetAgentId);
        SdlcStepDO requestedTargetStep = targetSdlcId == null ? null : sdlcResolver.resolveStep(
                tenantId, targetSdlcId, plan.getString("targetStepId"), plan.getString("targetStepHint"));
        if (targetSdlcId == null) {
            log.warn("interaction plan SDLC unresolved sideDispatchId={} targetAgentId={}",
                    sideDispatchId, targetAgentId);
            return null;
        }

        ReworkDecision decision = transactionTemplate.execute(status -> {
            var lockedWorkitem = lockWorkitemIfPresent(tenantId, side.getWorkitemId());
            if (lockedWorkitem == null) {
                log.info("interaction plan ignored because workitem is missing sideDispatchId={} workitemId={}",
                        sideDispatchId, side.getWorkitemId());
                return null;
            }
            List<DispatchDO> rows = dispatchDao.listByWorkitem(tenantId, side.getWorkitemId());
            DispatchDO activeMain = latest(rows, row -> !dispatchService.isInteractionDispatch(row)
                    && PAUSEABLE.contains(row.getStatus()));
            DispatchDO targetSource = targetInCurrentDelivery(rows, targetAgentId);
            if (targetSource == null
                    && Objects.equals(side.getAgentId(), targetAgentId)) {
                targetSource = side;
            }
            if (targetSource == null) {
                log.info("interaction plan ignored for worker outside current delivery sideDispatchId={} targetAgentId={}",
                        sideDispatchId, targetAgentId);
                return null;
            }
            SdlcStepDO targetStep = requestedTargetStep;
            if ((targetStep == null || targetStep.getId() == null) && side.getSdlcStepId() != null) {
                targetStep = sdlcResolver.resolveStep(tenantId, targetSdlcId,
                        String.valueOf(side.getSdlcStepId()), null);
            }
            if (targetStep == null || targetStep.getId() == null) {
                // Model hints are advisory. Fall back to the authoritative SDLC entry
                // instead of rejecting an otherwise valid formal-work request.
                targetStep = sdlcResolver.firstStep(tenantId, targetSdlcId);
            }
            if (targetStep == null || targetStep.getId() == null) {
                log.warn("interaction plan target unresolved sideDispatchId={} targetAgentId={}",
                        sideDispatchId, targetAgentId);
                return null;
            }
            Long waitFor = null;
            if (activeMain != null) {
                if (DispatchStatus.PENDING.equals(activeMain.getStatus())
                        || DispatchStatus.PACKAGING.equals(activeMain.getStatus())) {
                    if (!dispatchService.cancelUndeliveredForInteraction(tenantId, activeMain.getId())) {
                        activeMain = dispatchDao.findById(activeMain.getId());
                    } else {
                        activeMain = null;
                    }
                }
                if (activeMain != null && !DispatchStatus.isTerminal(activeMain.getStatus())
                        && !DispatchStatus.PAUSED.equals(activeMain.getStatus())) {
                    waitFor = activeMain.getId();
                }
            }
            DispatchDO rework = dispatchService.enqueueInteractionRework(tenantId, side.getWorkitemId(),
                    targetAgentId, targetStep.getId(), targetSource.getId(),
                    sideDispatchId, waitFor, 0L);
            // Only the newest comment may own delivery. Supersede every older fence,
            // including one waiting for a different predecessor, so a later release of
            // an obsolete barrier cannot start a stale rework after a newer request.
            for (DispatchDO row : rows) {
                if (row != null && row.getId() != null && rework.getId() != null
                        && row.getId() < rework.getId()
                        && DispatchStatus.WAITING_FOR_PAUSE.equals(row.getStatus())) {
                    dispatchService.cancelWaitingInteractionRework(tenantId, row.getId());
                }
            }
            if (waitFor == null && DispatchStatus.WAITING_FOR_PAUSE.equals(rework.getStatus())) {
                activateReworkInTransaction(tenantId, rework);
            }
            return new ReworkDecision(rework, waitFor);
        });
        if (decision == null) {
            return null;
        }
        Long waitFor = decision.waitForDispatchId();
        DispatchDO rework = decision.rework();
        if (waitFor != null && DispatchStatus.WAITING_FOR_PAUSE.equals(rework.getStatus())) {
            try {
                DispatchDO pauseState = pauseService.requestPause(
                        tenantId, side.getWorkitemId(), waitFor, 0L);
                if (pauseState != null && DispatchStatus.PAUSED.equals(pauseState.getStatus())) {
                    activateLatestWaitingRework(tenantId, side.getWorkitemId(), waitFor);
                }
            } catch (RuntimeException pauseFailure) {
                DispatchDO current = dispatchDao.findById(waitFor);
                if (current != null && (DispatchStatus.PAUSED.equals(current.getStatus())
                        || DispatchStatus.isTerminal(current.getStatus()))) {
                    activateLatestWaitingRework(tenantId, side.getWorkitemId(), waitFor);
                } else if (current != null && DispatchStatus.PAUSING.equals(current.getStatus())) {
                    // The PAUSING write is durable but transport outcome is ambiguous. Keep the
                    // waiter: a late PAUSED receipt can activate it, and a retry can resend pause.
                    throw pauseFailure;
                } else {
                    dispatchService.cancelWaitingInteractionRework(tenantId, rework.getId());
                    throw pauseFailure;
                }
            }
        }
        return rework;
    }

    public void onPaused(long tenantId, long pausedDispatchId) {
        DispatchDO paused = dispatchDao.findById(pausedDispatchId);
        if (paused != null && paused.getTenantId() == tenantId && paused.getWorkitemId() != null) {
            activateLatestWaitingRework(tenantId, paused.getWorkitemId(), pausedDispatchId);
        }
    }

    /**
     * Callback and compensation entry point for a predecessor that may have released
     * its pause barrier. The durable facts are re-read inside the activation lock.
     */
    public void reconcileWaitersForPredecessor(long tenantId, long predecessorId) {
        DispatchDO predecessor = dispatchDao.findById(predecessorId);
        if (predecessor != null && predecessor.getTenantId() == tenantId
                && predecessor.getWorkitemId() != null && waitersReleasable(predecessor)) {
            activateLatestWaitingRework(tenantId, predecessor.getWorkitemId(), predecessor.getId());
        }
    }

    public void reconcileReleasedWaiters(long tenantId, long executorId) {
        reconcileReleasedWaiterBatches(executorId, tenantId);
    }

    public void reconcileReleasedWaiters() {
        reconcileReleasedWaiterBatches(null, null);
    }

    private void reconcileReleasedWaiterBatches(Long executorId, Long tenantId) {
        long afterId = 0;
        while (true) {
            List<DispatchDO> batch = safe(dispatchDao.listReleasedPausePredecessors(
                    executorId, afterId, RELEASE_BATCH));
            if (batch.isEmpty()) return;
            for (DispatchDO predecessor : batch) {
                if (predecessor != null
                        && (tenantId == null || Objects.equals(predecessor.getTenantId(), tenantId))
                        && predecessor.getWorkitemId() != null
                        && waitersReleasable(predecessor)) {
                    activateLatestWaitingRework(predecessor.getTenantId(),
                            predecessor.getWorkitemId(), predecessor.getId());
                }
            }
            Long lastId = batch.get(batch.size() - 1) == null
                    ? null : batch.get(batch.size() - 1).getId();
            if (batch.size() < RELEASE_BATCH || lastId == null || lastId <= afterId) return;
            afterId = lastId;
        }
    }

    private static List<DispatchDO> safe(List<DispatchDO> rows) {
        return rows == null ? List.of() : rows;
    }

    private boolean waitersReleasable(DispatchDO predecessor) {
        if (predecessor == null) {
            return false;
        }
        if (recovery != null) {
            return recovery.canReleasePauseWaiter(predecessor);
        }
        if (DispatchStatus.PAUSED.equals(predecessor.getStatus())) {
            return true;
        }
        return DispatchStatus.CANCELED.equals(predecessor.getStatus())
                && predecessor.getError() != null
                && predecessor.getError().startsWith(DispatchFailureReason.PAUSE_CONFIRMATION_MISSING + ":");
    }

    private void activateLatestWaitingRework(long tenantId, long workitemId, long releasedDispatchId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (lockWorkitemIfPresent(tenantId, workitemId) == null) {
                log.info("waiting interaction rework ignored because workitem is missing workitemId={} releasedDispatchId={}",
                        workitemId, releasedDispatchId);
                return;
            }
            lockControlRow(tenantId, workitemId);
            DispatchDO released = dispatchDao.findById(releasedDispatchId);
            if (released == null || released.getTenantId() != tenantId
                    || !Objects.equals(released.getWorkitemId(), workitemId)
                    || !waitersReleasable(released)) {
                return;
            }
            List<DispatchDO> rows = safe(dispatchDao.listByWorkitem(tenantId, workitemId));
            List<DispatchDO> waiters = rows.stream()
                    .filter(Objects::nonNull)
                    .filter(waiter -> waiter.getId() != null
                            && DispatchStatus.WAITING_FOR_PAUSE.equals(waiter.getStatus()))
                    .sorted(Comparator.comparingLong(DispatchDO::getId).reversed())
                    .toList();
            if (waiters.isEmpty()) {
                return;
            }
            DispatchDO latest = waiters.get(0);
            String barrier = "waitForDispatchId=" + releasedDispatchId;
            if (!Objects.equals(latest.getResultSummary(), barrier)) {
                // A newer waiter owns another barrier. Waiters for the released
                // predecessor are stale and must not start after the newer request.
                for (DispatchDO waiter : waiters) {
                    if (Objects.equals(waiter.getResultSummary(), barrier)) {
                        cancelWaiter(tenantId, waiter);
                    }
                }
                return;
            }
            if (deliveryNoLongerAllowsActivation(tenantId, workitemId, latest, rows)) {
                for (DispatchDO waiter : waiters) {
                    cancelWaiter(tenantId, waiter);
                }
                return;
            }
            for (int i = 1; i < waiters.size(); i++) {
                cancelWaiter(tenantId, waiters.get(i));
            }
            activateReworkInTransaction(tenantId, latest);
        });
    }

    private boolean deliveryNoLongerAllowsActivation(long tenantId, long workitemId,
            DispatchDO latest, List<DispatchDO> rows) {
        if (recovery != null) {
            if (recovery.closed(tenantId, workitemId)) {
                log.info("waiting interaction rework canceled because delivery is closed workitemId={} reworkId={}",
                        workitemId, latest.getId());
                return true;
            }
            if (recovery.cancelRequested(tenantId, latest.getId())) {
                log.info("waiting interaction rework canceled because it was fenced workitemId={} reworkId={}",
                        workitemId, latest.getId());
                return true;
            }
            if (recovery.restartAfter(tenantId, workitemId, latest.getGmtCreate())) {
                log.info("waiting interaction rework canceled because a newer restart was allocated workitemId={} reworkId={}",
                        workitemId, latest.getId());
                return true;
            }
        }
        boolean newerFormal = rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getId() != null && row.getId() > latest.getId())
                .filter(row -> !dispatchService.isInteractionDispatch(row))
                .anyMatch(row -> row.executionSourceType() == ExecutionSourceType.WORKITEM);
        if (newerFormal) {
            log.info("waiting interaction rework canceled because a newer formal execution exists workitemId={} reworkId={}",
                    workitemId, latest.getId());
        }
        return newerFormal;
    }

    private void cancelWaiter(long tenantId, DispatchDO waiter) {
        if (!dispatchService.cancelWaitingInteractionRework(tenantId, waiter.getId())) {
            log.info("waiting interaction rework was already superseded reworkId={}", waiter.getId());
        }
    }

    private void lockControlRow(long tenantId, long workitemId) {
        if (recovery == null) {
            return;
        }
        DispatchDO subject = new DispatchDO();
        subject.setTenantId(tenantId);
        subject.setWorkitemId(workitemId);
        subject.setSourceType(ExecutionSourceType.WORKITEM.name());
        recovery.lockWorkitemControl(subject);
    }

    private void activateReworkInTransaction(long tenantId, DispatchDO rework) {
        Long targetSdlcId = sdlcResolver.resolveSdlcId(tenantId, rework.getAgentId());
        SdlcStepDO targetStep = targetSdlcId == null || rework.getSdlcStepId() == null ? null
                : sdlcResolver.resolveStep(tenantId, targetSdlcId, String.valueOf(rework.getSdlcStepId()), null);
        if (targetSdlcId != null && rework.getSdlcStepId() != null && targetStep == null) {
            // A queued step can outlive a change to the agent default. Preserve only a still-valid bound flow.
            WorkitemDO bound = lockWorkitemIfPresent(tenantId, rework.getWorkitemId());
            if (bound != null && bound.getSdlcId() != null) {
                targetStep = sdlcResolver.resolveStep(tenantId, bound.getSdlcId(),
                        String.valueOf(rework.getSdlcStepId()), null);
                if (targetStep != null) targetSdlcId = bound.getSdlcId();
            }
        }
        if (targetStep == null) {
            throw new IllegalStateException("comment rework target is no longer valid");
        }
        workitemService.rebindForInteractionRework(tenantId, rework.getWorkitemId(),
                rework.getAgentId(), targetSdlcId, rework.getSdlcStepId(), 0L);
        if (!dispatchService.releaseInteractionRework(tenantId, rework.getId())) {
            throw new IllegalStateException("comment rework release lost optimistic-lock race");
        }
        eventPublisher.publishEvent(new GuidanceDispatchQueuedEvent(tenantId, rework.getId()));
    }

    private WorkitemDO lockWorkitemIfPresent(long tenantId, long workitemId) {
        return workitemDao.findByIdForUpdate(workitemId, tenantId);
    }

    private record ReworkDecision(DispatchDO rework, Long waitForDispatchId) {}

    private DispatchDO latest(List<DispatchDO> rows, java.util.function.Predicate<DispatchDO> predicate) {
        if (rows == null) {
            return null;
        }
        return rows.stream().filter(Objects::nonNull).filter(predicate)
                .max(Comparator.comparingLong(row -> row.getId() == null ? 0L : row.getId()))
                .orElse(null);
    }

    private DispatchDO targetInCurrentDelivery(List<DispatchDO> rows, long targetAgentId) {
        if (rows == null) {
            return null;
        }
        List<DispatchDO> formal = rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> !dispatchService.isInteractionDispatch(row))
                .sorted(Comparator.comparingLong(
                        (DispatchDO row) -> row.getId() == null ? 0L : row.getId()).reversed())
                .toList();
        for (DispatchDO row : formal) {
            if (Objects.equals(row.getAgentId(), targetAgentId)) {
                return row;
            }
            if ("COMMENT_REWORK".equals(row.getResumeMode())) {
                break;
            }
        }
        return null;
    }
}
