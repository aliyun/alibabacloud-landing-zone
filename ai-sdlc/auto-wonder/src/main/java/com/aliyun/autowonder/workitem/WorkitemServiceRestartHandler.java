package com.aliyun.autowonder.workitem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * Reassignment as an explicit delivery restart. Independent of WorkitemService so the
 * bean cycle with the dispatch module keeps flowing through the event seam: this
 * handler never touches a dispatch object, only workitem rows and published events.
 * The caller (assignAs) owns the assignee mutation, SDLC binding, operator and schedule
 * writes; this handler owns closed-delivery reopen, idempotent round allocation and the
 * restart event publication.
 */
@Component
public class WorkitemServiceRestartHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkitemServiceRestartHandler.class);

    private final WorkitemDao workitemDao;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private DeliveryRestartStore restartStore;

    public WorkitemServiceRestartHandler(WorkitemDao workitemDao, ApplicationEventPublisher eventPublisher) {
        this.workitemDao = workitemDao;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes the restart of the formal delivery for an AGENT-assigned workitem. A
     * future scheduledStartAt defers the event: the scheduled-start scanner publishes it
     * once the planned time arrives, keeping exactly one restart per round.
     */
    @Transactional
    public WorkitemDO restartAgentDelivery(long workitemId, long tenantId, Long agentId, Long sdlcStepId,
            String restartToken, Date scheduledStartAt, long modifierUserId, AssignmentActor actor) {
        String operator = actor == null ? "SYSTEM" : String.valueOf(actor.type());
        // A closed delivery reopens only for this authorized restart; existing logs,
        // artifacts and audit rows from previous rounds stay untouched.
        if (restartStore != null) {
            restartStore.recordClosedReopen(tenantId, workitemId, modifierUserId, operator);
        }
        int round = restartStore == null ? 1
                : restartStore.allocateRound(tenantId, workitemId, modifierUserId, operator,
                        restartToken, scheduledStartAt, sdlcStepId, agentId);
        String idempotencyKey = DeliveryRestartStore.restartIdempotencyKey(workitemId, round);
        WorkitemDO fresh = workitemDao.findById(workitemId);
        boolean deferred = scheduledStartAt != null && scheduledStartAt.after(new Date());
        if (deferred) {
            // Delayed restart: the scheduled-start scanner publishes the restart event
            // once the planned time arrives.
            log.info("workitem {} restart round {} deferred until {}", workitemId, round, scheduledStartAt);
            return fresh;
        }
        eventPublisher.publishEvent(new WorkitemDeliveryRestartedEvent(
                tenantId, workitemId, sdlcStepId, agentId, modifierUserId, idempotencyKey, scheduledStartAt));
        return fresh;
    }

    void bindRestartStore(DeliveryRestartStore store) {
        this.restartStore = store;
    }

    /**
     * Fires a deferred restart round immediately (user picked "execute now" before the
     * planned time). Returns false when no restart round is pending, so the caller keeps
     * the plain first-assignment event.
     */
    public boolean firePendingRestartNow(long tenantId, long workitemId, Long agentId, Long sdlcStepId,
            long userId) {
        if (restartStore == null) {
            return false;
        }
        Integer round = restartStore.pendingScheduledRound(tenantId, workitemId);
        if (round == null) {
            return false;
        }
        eventPublisher.publishEvent(new WorkitemDeliveryRestartedEvent(tenantId, workitemId, sdlcStepId,
                agentId, userId, DeliveryRestartStore.restartIdempotencyKey(workitemId, round), null));
        return true;
    }
}
