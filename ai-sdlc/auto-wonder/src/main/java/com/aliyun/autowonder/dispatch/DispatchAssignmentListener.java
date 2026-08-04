package com.aliyun.autowonder.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges a human AGENT-assignment into the dispatch engine. Lives in the
 * dispatch package so the workitem module never depends on DispatchService.
 * Fires AFTER_COMMIT so the external transport dispatch never runs against an
 * assignment that later rolls back.
 */
@Component
public class DispatchAssignmentListener {

    private static final Logger log = LoggerFactory.getLogger(DispatchAssignmentListener.class);
    private final DispatchService dispatchService;

    public DispatchAssignmentListener(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkitemAssigned(WorkitemAssignedEvent e) {
        if (e.getSdlcStepId() == null || e.getAgentId() == null) {
            log.info("workitem assigned skipped workitemId={} (no sdlcStep or agent)", e.getWorkitemId());
            return;
        }
        log.info("workitem assigned workitemId={} agentId={} sdlcStepId={}", e.getWorkitemId(), e.getAgentId(), e.getSdlcStepId());
        DispatchDO d = dispatchService.enqueueAssignment(e.getTenantId(), e.getWorkitemId(),
                e.getSdlcStepId(), e.getAgentId(), e.getAssignmentVersion(), e.getUserId());
        dispatchService.runPending(d.getId());
    }
}
