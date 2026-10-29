package com.aliyun.autowonder.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentConversationRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationRecoveryTask.class);

    private final AgentConversationService conversationService;

    public AgentConversationRecoveryTask(AgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Scheduled(fixedDelayString = "${autowonder.conversation.queue-recovery.fixed-delay-ms:10000}")
    public void recoverQueuedTurns() {
        try {
            conversationService.recoverQueuedTurns();
        } catch (RuntimeException e) {
            log.warn("conversation queued turn recovery scan failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${autowonder.squad.workflow.cancel-interval-ms:30000}")
    public void cancelInactiveWorkflowTurns() {
        try {
            conversationService.cancelInactiveWorkflowTurns();
        } catch (RuntimeException e) {
            log.warn("workflow cancellation sweep failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${autowonder.conversation.recovery.fixed-delay-ms:60000}")
    public void recoverStaleTurns() {
        try {
            conversationService.recoverStaleTurns();
        } catch (RuntimeException e) {
            log.warn("conversation stale turn recovery scan failed", e);
        }
    }
}
