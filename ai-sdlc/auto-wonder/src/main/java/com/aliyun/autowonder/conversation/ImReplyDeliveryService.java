package com.aliyun.autowonder.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * Durable delivery queue for IM outbound replies. The database row is the source of truth: a
 * claim leases the row to exactly one worker, and every transition is guarded by the claimed
 * attempt so a stale worker can never overwrite a newer outcome.
 */
@Service
public class ImReplyDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(ImReplyDeliveryService.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_SENDING = "SENDING";
    static final String STATUS_SENT = "SENT";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_CANCELED = "CANCELED";
    static final String STATUS_UNKNOWN = "UNKNOWN";

    static final String CHANNEL_DINGTALK = "DINGTALK";
    static final String CHANNEL_FEISHU = "FEISHU";

    static final int LEASE_SECONDS = 120;
    static final int SCAN_BATCH_SIZE = 50;
    static final int MAX_DELIVERY_ATTEMPTS = 5;
    static final int FEISHU_MAX_ATTEMPTS = 3;
    static final long FEISHU_DEDUP_WINDOW_MILLIS = 60 * 60 * 1000L;
    static final long RETRY_BACKOFF_STEP_MILLIS = 10_000L;
    static final long RETRY_BACKOFF_MAX_MILLIS = 120_000L;
    private static final int MAX_ERROR_LENGTH = 1000;

    static final String ERROR_DINGTALK_LEASE_EXPIRED =
            "钉钉回复发送结果未知（租约过期），未自动重发";
    static final String ERROR_FEISHU_LEASE_EXPIRED =
            "飞书回复发送结果未知（租约过期且已超出重试窗口）";
    static final String ERROR_MISSING_SOURCE = "缺少原入站消息，回复未投递";
    static final String ERROR_MISSING_CONVERSATION = "会话不存在，回复未投递";
    static final String ERROR_EMPTY_REPLY = "回复内容为空，未投递";
    static final String ERROR_NO_SINK = "该渠道不支持主动回复，未投递";

    private final AgentConversationTurnDao turnDao;
    private final AgentConversationDao convDao;
    private final ConversationChannelSinkRegistry sinkRegistry;
    private TransactionTemplate deliveryTransactionTemplate;

    public ImReplyDeliveryService(AgentConversationTurnDao turnDao, AgentConversationDao convDao,
            ConversationChannelSinkRegistry sinkRegistry) {
        this.turnDao = turnDao;
        this.convDao = convDao;
        this.sinkRegistry = sinkRegistry;
    }

    @Autowired(required = false)
    void configureDeliveryTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.deliveryTransactionTemplate = template;
    }

    /** Restart recovery: any due PENDING or expired SENDING row is claimed and processed once. */
    @Scheduled(fixedDelayString = "${autowonder.conversation.im-delivery.fixed-delay-ms:5000}")
    public void deliverPending() {
        List<Long> ids = turnDao.listDueDeliveryTurnIds(SCAN_BATCH_SIZE);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            try {
                deliver(id);
            } catch (RuntimeException e) {
                log.warn("IM reply delivery scan failed outTurnId={} errorType={}", id,
                        e.getClass().getSimpleName());
            }
        }
    }

    /** Post-commit wake-up. Safe to call alongside the scheduled scan: the claim is exclusive. */
    public void deliver(Long outTurnId) {
        if (outTurnId == null) {
            return;
        }
        AgentConversationTurnDO turn = turnDao.findDeliveryTurn(outTurnId);
        if (turn == null || !isDeliverableState(turn.getDeliveryStatus())) {
            return;
        }
        AgentConversationDO conv = convDao.findById(turn.getTenantId(), turn.getConversationId());
        if (conv == null) {
            cancel(turn.getId(), ERROR_MISSING_CONVERSATION);
            return;
        }
        ConversationChannelSink sink = sinkRegistry.find(conv.getChannel());
        if (sink == null) {
            cancel(turn.getId(), ERROR_NO_SINK);
            return;
        }
        AgentConversationTurnDO claimed = claim(turn, conv.getChannel());
        if (claimed == null) {
            return;
        }
        int attempt = attemptOf(claimed);
        try {
            AgentConversationTurnDO source = claimed.getSourceInboundTurnId() == null ? null
                    : turnDao.findByConversationTurn(claimed.getTenantId(),
                            claimed.getConversationId(), claimed.getSourceInboundTurnId());
            if (source == null) {
                finish(claimed, attempt, STATUS_CANCELED, ERROR_MISSING_SOURCE, null);
                return;
            }
            if (claimed.getContent() == null || claimed.getContent().isBlank()) {
                finish(claimed, attempt, STATUS_CANCELED, ERROR_EMPTY_REPLY, null);
                return;
            }
            sink.deliverReply(conv, claimed.getContent(), source.getExternalMsgId());
            finish(claimed, attempt, STATUS_SENT, null, null);
            log.info("IM reply delivered conversationId={} outTurnId={} channel={} attempt={}",
                    conv.getId(), claimed.getId(), conv.getChannel(), attempt);
        } catch (RuntimeException failure) {
            markFailure(conv, claimed, attempt, failure);
        }
    }

    private AgentConversationTurnDO claim(AgentConversationTurnDO turn, String channel) {
        // The claim must commit before the sink is invoked: afterCommit callbacks still run on
        // the committed transaction's bound session, and an uncommitted claim would survive a
        // crash as PENDING and trigger a duplicate resend.
        return inTransaction(() -> claimNow(turn, channel));
    }

    private AgentConversationTurnDO claimNow(AgentConversationTurnDO turn, String channel) {
        Date leaseUntil = new Date(System.currentTimeMillis() + LEASE_SECONDS * 1000L);
        if (STATUS_PENDING.equals(turn.getDeliveryStatus())) {
            return turnDao.claimPendingDelivery(turn.getId(), leaseUntil) == 1
                    ? turnDao.findDeliveryTurn(turn.getId()) : null;
        }
        Date availableAt = turn.getDeliveryAvailableAt();
        if (availableAt == null || availableAt.getTime() > System.currentTimeMillis()) {
            return null;
        }
        if (CHANNEL_DINGTALK.equals(channel)) {
            // DingTalk has no idempotency key: an expired lease must never be resent blindly.
            if (turnDao.expireUnconfirmedDelivery(turn.getId(), ERROR_DINGTALK_LEASE_EXPIRED) == 1) {
                log.warn("IM reply delivery marked UNKNOWN outTurnId={} channel={} reason=lease_expired",
                        turn.getId(), channel);
            }
            return null;
        }
        Date retryWindowStart = new Date(System.currentTimeMillis() - FEISHU_DEDUP_WINDOW_MILLIS);
        if (turnDao.claimExpiredDelivery(turn.getId(), leaseUntil, FEISHU_MAX_ATTEMPTS,
                retryWindowStart) == 1) {
            return turnDao.findDeliveryTurn(turn.getId());
        }
        if (turnDao.expireUnconfirmedDelivery(turn.getId(), ERROR_FEISHU_LEASE_EXPIRED) == 1) {
            log.warn("IM reply delivery marked UNKNOWN outTurnId={} channel={} reason=retry_exhausted",
                    turn.getId(), channel);
        }
        return null;
    }

    private void markFailure(AgentConversationDO conv, AgentConversationTurnDO turn, int attempt,
            RuntimeException failure) {
        String error = describe(failure);
        if (failure instanceof ImDeliveryTargetChangedException) {
            finish(turn, attempt, STATUS_CANCELED, error, null);
            log.info("IM reply canceled outTurnId={} channel={} attempt={} reason=target_changed",
                    turn.getId(), conv.getChannel(), attempt);
            return;
        }
        if (failure instanceof ImDeliveryFailedException) {
            finish(turn, attempt, STATUS_FAILED, error, null);
            log.warn("IM reply failed outTurnId={} channel={} attempt={}", turn.getId(),
                    conv.getChannel(), attempt);
            return;
        }
        if (failure instanceof ImDeliveryRetryableException) {
            if (attempt >= maxAttempts(conv.getChannel())) {
                finish(turn, attempt, STATUS_FAILED, error, null);
                log.warn("IM reply failed after retries outTurnId={} channel={} attempt={}",
                        turn.getId(), conv.getChannel(), attempt);
            } else {
                finish(turn, attempt, STATUS_PENDING, error, nextAttemptAt(attempt));
            }
            return;
        }
        if (failure instanceof ImDeliveryUnknownException) {
            if (retryUnknown(conv.getChannel(), turn, attempt)) {
                finish(turn, attempt, STATUS_PENDING, error, nextAttemptAt(attempt));
            } else {
                finish(turn, attempt, STATUS_UNKNOWN, error, null);
                log.warn("IM reply outcome unknown outTurnId={} channel={} attempt={}",
                        turn.getId(), conv.getChannel(), attempt);
            }
            return;
        }
        // Unclassified failures may have reached the provider; never claim success.
        finish(turn, attempt, STATUS_UNKNOWN, error, null);
        log.warn("IM reply outcome unknown outTurnId={} channel={} attempt={} errorType={}",
                turn.getId(), conv.getChannel(), attempt, failure.getClass().getSimpleName());
    }

    private boolean retryUnknown(String channel, AgentConversationTurnDO turn, int attempt) {
        if (!CHANNEL_FEISHU.equals(channel) || attempt >= FEISHU_MAX_ATTEMPTS) {
            return false;
        }
        Date created = turn.getGmtCreate();
        return created != null
                && created.getTime() > System.currentTimeMillis() - FEISHU_DEDUP_WINDOW_MILLIS;
    }

    private int maxAttempts(String channel) {
        return CHANNEL_DINGTALK.equals(channel) ? MAX_DELIVERY_ATTEMPTS : FEISHU_MAX_ATTEMPTS;
    }

    private Date nextAttemptAt(int attempt) {
        long backoff = Math.min(RETRY_BACKOFF_STEP_MILLIS * attempt, RETRY_BACKOFF_MAX_MILLIS);
        return new Date(System.currentTimeMillis() + backoff);
    }

    private void cancel(Long turnId, String error) {
        Integer updated = inTransaction(
                () -> turnDao.cancelUndeliverableDelivery(turnId, error));
        if (updated == null || updated != 1) {
            log.warn("IM reply delivery cancel not applied outTurnId={} error={}", turnId, error);
        }
    }

    private void finish(AgentConversationTurnDO turn, int attempt, String status, String error,
            Date availableAt) {
        Integer updated = inTransaction(
                () -> turnDao.finishDelivery(turn.getId(), attempt, status, error, availableAt));
        if (updated == null || updated != 1) {
            log.warn("IM reply delivery state not applied outTurnId={} attempt={} status={}",
                    turn.getId(), attempt, status);
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (deliveryTransactionTemplate == null) {
            return work.get();
        }
        return deliveryTransactionTemplate.execute(status -> work.get());
    }

    private boolean isDeliverableState(String status) {
        return STATUS_PENDING.equals(status) || STATUS_SENDING.equals(status);
    }

    private int attemptOf(AgentConversationTurnDO turn) {
        return turn.getDeliveryAttempt() == null ? 0 : turn.getDeliveryAttempt();
    }

    private String describe(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        String trimmed = message.replace('\n', ' ').replace('\r', ' ').trim();
        return trimmed.length() > MAX_ERROR_LENGTH
                ? trimmed.substring(0, MAX_ERROR_LENGTH) : trimmed;
    }
}
