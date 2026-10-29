package com.aliyun.autowonder.conversation;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.aliyun.autowonder.common.i18n.SystemMessages;

/** Separate transaction: never acquire conversation advisory locks while holding delivery row locks. */
@Service
public class ConversationAnswerTerminationService {
    private final AgentConversationService conversations;
    private final AgentConversationElicitationDao dao;
    private final ConversationTransport transport;
    public ConversationAnswerTerminationService(@Lazy AgentConversationService conversations,
            AgentConversationElicitationDao dao, ConversationTransport transport) {
        this.conversations=conversations;this.dao=dao;this.transport=transport;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void terminate(AgentConversationElicitationDO row) {
        conversations.acknowledgeTurn(row.getTenantId(),row.getExecutorId(),row.getConversationId(),row.getTurnId(),
                "FAILED",SystemMessages.render("conversation.answer_delivery_unconfirmed",null,
                        "Answer delivery could not be confirmed"),null,null,null,row.getDispatchAttempt());
        // Keep durable stop intent until the exact runtime terminal acknowledgement.
        // Retry at most the existing 35-minute local human-wait backstop beyond delivery expiry.
        if (row.getDeliveryDeadline()!=null && System.currentTimeMillis()>row.getDeliveryDeadline().getTime()+35*60_000L) {
            dao.finishAnswerDelivery(row.getId(),row.getAnswerId());
            return;
        }
        AgentConversationDO original=new AgentConversationDO();
        original.setId(row.getConversationId()); original.setExecutorId(row.getExecutorId());
        Runnable stop=()-> {
            try { transport.sendCancelWithAttempt(original,row.getTurnId(),row.getDispatchAttempt()); }
            catch (RuntimeException ignored) { /* Persisted next_delivery_at retains this exact-attempt stop for another sweep. */ }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { stop.run(); }
            });
        } else stop.run();
    }
}
