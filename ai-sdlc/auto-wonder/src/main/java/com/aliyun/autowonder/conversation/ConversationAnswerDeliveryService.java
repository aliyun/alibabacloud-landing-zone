package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/** Durable answer intent; socket delivery is performed separately on committed rows. */
@Service
public class ConversationAnswerDeliveryService {
    private final AgentConversationElicitationDao dao;
    private final AgentConversationTurnDao turns;
    private final AgentConversationDao conversations;
    private final ConversationTransport transport;
    private ConversationAnswerTerminationService terminationService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTerminationService(ConversationAnswerTerminationService service) {
        this.terminationService = service;
    }
    @Value("${autowonder.conversation.elicitation.timeout-minutes:30}")
    private int answerTimeoutMinutes = 30;
    @Value("${autowonder.conversation.elicitation.delivery-timeout-seconds:300}")
    private int deliveryTimeoutSeconds = 300;

    public ConversationAnswerDeliveryService(AgentConversationElicitationDao dao,
            AgentConversationTurnDao turns, AgentConversationDao conversations,
            ConversationTransport transport) {
        this.dao=dao; this.turns=turns; this.conversations=conversations; this.transport=transport;
    }

    @Transactional
    public void submit(long tenantId, long conversationId, String requestId, String action, String answerJson) {
        AgentConversationElicitationDO row=dao.findByRequestId(tenantId,conversationId,requestId);
        if (row==null || !Integer.valueOf(1).equals(row.getAnswerProtocolVersion())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        String status="accept".equals(action)?"ANSWERED":"DECLINED";
        if (row.getAnswerId()!=null) {
            if (status.equals(row.getStatus()) && Objects.equals(
                    answerJson==null?null:JSON.parse(answerJson),
                    row.getAnswerJson()==null?null:JSON.parse(row.getAnswerJson()))) return;
            throw new BizException(ErrorCode.CONFLICT);
        }
        AgentConversationTurnDO turn=turns.findByConversationTurnForUpdate(tenantId,conversationId,row.getTurnId());
        row=dao.findByRequestIdForUpdate(tenantId,conversationId,requestId);
        if (row==null) throw new BizException(ErrorCode.NOT_FOUND);
        if (row.getAnswerId()!=null) {
            if (status.equals(row.getStatus()) && Objects.equals(answerJson==null?null:JSON.parse(answerJson),
                    row.getAnswerJson()==null?null:JSON.parse(row.getAnswerJson()))) return;
            throw new BizException(ErrorCode.CONFLICT);
        }
        AgentConversationDO conv=conversations.findById(tenantId,conversationId);
        if (turn==null || !"PROCESSING".equals(turn.getStatus())
                || AgentConversationService.questionExpiryStop(turn) != null
                || !Objects.equals(turn.getDispatchAttempt(),row.getDispatchAttempt())
                || conv==null || !Objects.equals(conv.getExecutorId(),row.getExecutorId())) {
            throw BizException.keyed(ErrorCode.CONFLICT,"conversation.elicitation_turn_inactive",
                    new Object[]{String.valueOf(row.getTurnId())},"elicitation turn is no longer processing");
        }
        long now=System.currentTimeMillis();
        if (!"PENDING".equals(row.getStatus()) || row.getGmtCreate()==null
                || row.getGmtCreate().getTime()+Math.max(1,answerTimeoutMinutes)*60_000L<=now) {
            throw BizException.keyed(ErrorCode.CONFLICT,"conversation.elicitation_settled",
                    new Object[]{row.getStatus()},"elicitation no longer accepts answers");
        }
        row.setStatus(status); row.setAnswerJson(answerJson);
        row.setAnswerId(UUID.randomUUID().toString()); row.setDeliveryStatus("SAVED");
        row.setDeliveryDeadline(new Date(now+Math.max(1,deliveryTimeoutSeconds)*1000L));
        row.setNextDeliveryAt(new Date(now));
        if (dao.saveAnswerIfPending(row)!=1) throw new BizException(ErrorCode.CONFLICT);
    }
    private AgentConversationService expiryCoordinator;
    @org.springframework.beans.factory.annotation.Autowired
    public void setExpiryCoordinator(@org.springframework.context.annotation.Lazy AgentConversationService service) {
        this.expiryCoordinator = service;
    }

    // Coordination must acquire the conversation lock before any turn/question row lock.
    public boolean expireQuestion(AgentConversationElicitationDO candidate, Date cutoff) {
        return expiryCoordinator != null && expiryCoordinator.expireQuestion(candidate, cutoff);
    }

    @Transactional
    public void receive(long tenantId,long executorId,long conversationId,long turnId,int attempt,
            String requestId,String answerId,String stage) {
        if (!java.util.Set.of("RECEIVED","WRITTEN","UNAVAILABLE","UNKNOWN").contains(stage == null ? "" : stage)) return;
        var row=dao.findByRequestId(tenantId,conversationId,requestId);
        if (row==null || !humanAnswer(row) || !Integer.valueOf(1).equals(row.getAnswerProtocolVersion())
                || !Objects.equals(row.getExecutorId(),executorId) || !Objects.equals(row.getTurnId(),turnId)
                || !Objects.equals(row.getDispatchAttempt(),attempt) || answerId==null
                || !answerId.equals(row.getAnswerId())) return;
        dao.advanceAnswerDelivery(row.getId(),answerId,stage);
    }

    @Transactional
    public void deliver(AgentConversationElicitationDO candidate) {
        Date now=new Date();
        var turn=turns.findByConversationTurnForUpdate(candidate.getTenantId(),candidate.getConversationId(),candidate.getTurnId());
        if (dao.claimAnswerDelivery(candidate.getId(),now,new Date(now.getTime()+5000))!=1) return;
        var row=dao.findByRequestId(candidate.getTenantId(),candidate.getConversationId(),candidate.getRequestId());
        if (row==null || !humanAnswer(row)) return;
        if (AgentConversationService.questionExpiryStop(turn) != null) {
            dao.closeAnswerDeliveriesForTurn(row.getTenantId(),row.getConversationId(),row.getTurnId());
            return;
        }
        if (java.util.Set.of("UNKNOWN","UNAVAILABLE").contains(row.getDeliveryStatus())) { terminate(row); return; }
        if (!java.util.Set.of("SAVED","RECEIVED").contains(row.getDeliveryStatus())) return;
        if (row.getDeliveryDeadline()==null || !row.getDeliveryDeadline().after(now)) {
            dao.advanceAnswerDelivery(row.getId(),row.getAnswerId(),"UNKNOWN"); terminate(row); return;
        }
        var conv=conversations.findById(row.getTenantId(),row.getConversationId());
        boolean processing=turn!=null && "PROCESSING".equals(turn.getStatus());
        boolean terminal=turn!=null && java.util.Set.of("SUCCESS","FAILED","CANCELED")
                .contains(turn.getStatus()==null?"":turn.getStatus());
        // A later turn may rebind the conversation; terminal receipt queries still target
        // the saved answer's original executor. Active answers retain current-owner fencing.
        if (turn==null || !Objects.equals(turn.getDispatchAttempt(),row.getDispatchAttempt())
                || conv==null || (processing && !Objects.equals(conv.getExecutorId(),row.getExecutorId()))
                || (!processing && (!terminal || row.getExecutorId()==null || row.getExecutorId()<=0
                        || row.getDispatchAttempt()==null || row.getDispatchAttempt()<=0))) {
            dao.advanceAnswerDelivery(row.getId(),row.getAnswerId(),"UNKNOWN"); terminate(row); return;
        }
        row.setReceiptOnly(terminal);
        Runnable send=()-> {
            long remaining=row.getDeliveryDeadline().getTime()-System.currentTimeMillis();
            if (remaining<=0) return;
            try { sendCommittedAnswer(conv, row, remaining); }
            catch (RuntimeException ignored) {
                // Persisted intent remains eligible; never log answer or transport exception payload.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("answer delivery deferred elicitationId={}",row.getId());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { send.run(); }
                });
        } else send.run();
    }

    private org.springframework.transaction.support.TransactionTemplate sendTransaction;

    @org.springframework.beans.factory.annotation.Autowired
    void configureSendTransaction(org.springframework.transaction.PlatformTransactionManager manager) {
        sendTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        sendTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private void sendCommittedAnswer(AgentConversationDO conv, AgentConversationElicitationDO row, long remaining) {
        java.util.function.Supplier<Boolean> admit = () -> {
            // Fresh admission linearizes with expiry under the turn lock. Release that lock
            // before transport: a human answer admitted first may race a later stop.
            var current = turns.findByConversationTurnForUpdate(row.getTenantId(),row.getConversationId(),row.getTurnId());
            if (current == null || !Objects.equals(current.getDispatchAttempt(),row.getDispatchAttempt())) return false;
            if (AgentConversationService.questionExpiryStop(current) != null) {
                dao.closeAnswerDeliveriesForTurn(row.getTenantId(),row.getConversationId(),row.getTurnId());
                return false;
            }
            row.setReceiptOnly(!"PROCESSING".equals(current.getStatus()));
            return true;
        };
        boolean admitted = Boolean.TRUE.equals(sendTransaction == null ? admit.get()
                : sendTransaction.execute(status -> admit.get()));
        long ttl = Math.min(remaining, row.getDeliveryDeadline().getTime()-System.currentTimeMillis());
        if (admitted && ttl > 0) transport.sendReliableElicitationReply(conv,row,ttl);
    }

    private boolean humanAnswer(AgentConversationElicitationDO row) {
        return "ANSWERED".equals(row.getStatus()) || "DECLINED".equals(row.getStatus());
    }

    public void turnTerminated(long tenantId,long executorId,long conversationId,long turnId,int attempt,String status) {
        if (attempt>0 && java.util.Set.of("SUCCESS","FAILED","CANCELED").contains(status==null?"":status))
            dao.finishAnswerDeliveriesForTurn(tenantId,executorId,conversationId,turnId,attempt);
    }

    private void terminate(AgentConversationElicitationDO row) {
        Runnable stop = () -> { if (terminationService != null) terminationService.terminate(row); };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { stop.run(); }
                });
        } else stop.run();
    }
}
