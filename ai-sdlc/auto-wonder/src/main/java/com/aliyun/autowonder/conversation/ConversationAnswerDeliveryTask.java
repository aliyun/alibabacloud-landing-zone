package com.aliyun.autowonder.conversation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Date;

@Component
public class ConversationAnswerDeliveryTask {
    private final AgentConversationElicitationDao dao;
    private final ConversationAnswerDeliveryService service;
    public ConversationAnswerDeliveryTask(AgentConversationElicitationDao dao, ConversationAnswerDeliveryService service) {
        this.dao=dao; this.service=service;
    }
    @Scheduled(fixedDelayString="${autowonder.conversation.elicitation.delivery-sweep-ms:1000}")
    public void retry() {
        for (var row:dao.listDueAnswers(new Date(),100)) {
            try { service.deliver(row); }
            catch (RuntimeException ignored) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("answer delivery retry failed elicitationId={}",row.getId());
            }
        }
    }
}
