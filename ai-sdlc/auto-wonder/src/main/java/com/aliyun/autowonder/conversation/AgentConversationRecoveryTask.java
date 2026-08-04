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

    @Scheduled(fixedDelayString = "${autowonder.conversation.recovery.fixed-delay-ms:60000}")
    public void recoverStaleTurns() {
        try {
            conversationService.recoverStaleTurns();
        } catch (RuntimeException e) {
            log.warn("conversation stale turn recovery scan failed", e);
        }
    }
}
