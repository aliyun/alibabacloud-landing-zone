package com.aliyun.autowonder.squad.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Converges generations whose conversation was lost or whose deadline passed, and applies the
 * bounded retention window. Both loops are idempotent; row locks and the dispatch claim make
 * concurrent instances harmless.
 */
@Component
public class SquadWorkflowRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(SquadWorkflowRecoveryTask.class);

    private final SquadWorkflowService workflowService;

    public SquadWorkflowRecoveryTask(SquadWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Scheduled(fixedDelayString = "${autowonder.squad.workflow.recovery-interval-ms:30000}")
    public void recover() {
        try {
            workflowService.recover();
        } catch (RuntimeException e) {
            log.warn("squad workflow recovery pass failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${autowonder.squad.workflow.cleanup-interval-ms:3600000}")
    public void cleanup() {
        try {
            workflowService.cleanupExpired();
        } catch (RuntimeException e) {
            log.warn("squad workflow cleanup pass failed", e);
        }
    }
}
