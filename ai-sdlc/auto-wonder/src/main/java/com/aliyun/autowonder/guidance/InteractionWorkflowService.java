package com.aliyun.autowonder.guidance;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
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
            if (waitFor != null) {
                // Only the latest comment may own the pause barrier. Supersede older
                // waiters immediately instead of leaving multiple UI steps fenced.
                for (DispatchDO row : rows) {
                    if (row != null && !java.util.Objects.equals(row.getId(), rework.getId())
                            && DispatchStatus.WAITING_FOR_PAUSE.equals(row.getStatus())
                            && ("waitForDispatchId=" + waitFor).equals(row.getResultSummary())) {
                        dispatchService.cancelWaitingInteractionRework(tenantId, row.getId());
                    }
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
        if (paused != null && paused.getTenantId() == tenantId) {
            activateLatestWaitingRework(tenantId, paused.getWorkitemId(), pausedDispatchId);
        }
    }

    private void activateLatestWaitingRework(long tenantId, long workitemId, long pausedDispatchId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (lockWorkitemIfPresent(tenantId, workitemId) == null) {
                log.info("waiting interaction rework ignored because workitem is missing workitemId={} pausedDispatchId={}",
                        workitemId, pausedDispatchId);
                return;
            }
            List<DispatchDO> candidates = dispatchDao.listByWorkitem(tenantId, workitemId).stream()
                    .filter(candidate -> DispatchStatus.WAITING_FOR_PAUSE.equals(candidate.getStatus()))
                    .filter(candidate -> Objects.equals(candidate.getResultSummary(),
                            "waitForDispatchId=" + pausedDispatchId))
                    .sorted(Comparator.comparingLong(
                            (DispatchDO row) -> row.getId() == null ? 0L : row.getId()).reversed())
                    .toList();
            if (!candidates.isEmpty()) {
                for (int i = 1; i < candidates.size(); i++) {
                    dispatchService.cancelWaitingInteractionRework(tenantId, candidates.get(i).getId());
                }
                activateReworkInTransaction(tenantId, candidates.get(0));
            }
        });
    }

    private void activateReworkInTransaction(long tenantId, DispatchDO rework) {
        Long targetSdlcId = sdlcResolver.resolveSdlcId(tenantId, rework.getAgentId());
        if (targetSdlcId == null || rework.getSdlcStepId() == null
                || sdlcResolver.resolveStep(tenantId, targetSdlcId,
                String.valueOf(rework.getSdlcStepId()), null) == null) {
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
