package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.im.ImDeliveryException;
import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.ImSendCommand;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImNotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(ImNotificationWorker.class);
    private static final String PROVIDER = "DINGTALK";

    private final ImNotificationQueue queue;
    private final ImNotificationFormatter formatter;
    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImProviderRegistry providerRegistry;
    private final ImNotificationProperties properties;
    private final ImNotificationMessageContextResolver contextResolver;

    public ImNotificationWorker(ImNotificationQueue queue,
                                ImNotificationFormatter formatter,
                                UserImIdentityService identityService,
                                PlatformImChannelConfigService channelConfigService,
                                ImProviderRegistry providerRegistry,
                                ImNotificationProperties properties,
                                ImNotificationMessageContextResolver contextResolver) {
        this.queue = queue;
        this.formatter = formatter;
        this.identityService = identityService;
        this.channelConfigService = channelConfigService;
        this.providerRegistry = providerRegistry;
        this.properties = properties;
        this.contextResolver = contextResolver;
    }

    @Scheduled(fixedDelayString = "${autowonder.im.notification.poll-delay-ms:1000}")
    public void pollNew() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("IM notification poll new failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${autowonder.im.notification.recovery-delay-ms:1000}")
    public void recoverStale() {
        try {
            recoverOnce();
        } catch (Exception e) {
            log.error("IM notification recovery failed", e);
        }
    }

    void pollOnce() {
        processAll(queue.readNew(properties.getConsumer(), properties.getBatchSize()));
    }

    void recoverOnce() {
        processAll(queue.claimStale(properties.getConsumer(), properties.getBatchSize()));
    }

    void process(ImNotificationEnvelope envelope) {
        String previousRequestId = MDC.get("requestId");
        try {
            if (envelope.isValid() && hasText(envelope.task().requestId())) {
                MDC.put("requestId", envelope.task().requestId());
            } else {
                MDC.remove("requestId");
            }
            processWithMdc(envelope);
        } finally {
            if (previousRequestId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    private void processAll(List<ImNotificationEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return;
        }
        for (ImNotificationEnvelope envelope : envelopes) {
            try {
                process(envelope);
            } catch (Exception e) {
                log.error("IM notification message processing boundary failed messageId={}",
                        safeMessageId(envelope), e);
            }
        }
    }

    private void processWithMdc(ImNotificationEnvelope envelope) {
        if (!envelope.isValid()) {
            log.error("IM notification malformed payload dropped messageId={} reason={}",
                    envelope.messageId(), safeValue(envelope.errorReason()));
            queue.ack(envelope.messageId());
            return;
        }
        ImNotificationTask task = envelope.task();
        if (envelope.deliveryCount() > properties.getMaxAttempts()) {
            log.error("IM notification drop after max attempts messageId={} notificationKey={} deliveryCount={}",
                    envelope.messageId(), task.notificationKey(), envelope.deliveryCount());
            queue.ack(envelope.messageId());
            return;
        }
        if (queue.isDelivered(task.notificationKey())) {
            queue.ack(envelope.messageId());
            return;
        }

        try {
            UserImIdentityDO identity = identityService.find(task.recipientUserId(), PROVIDER);
            if (identity == null || !hasText(identity.getExternalUserId())) {
                log.warn("IM notification skipped missing identity messageId={} notificationKey={} recipientUserId={}",
                        envelope.messageId(), task.notificationKey(), task.recipientUserId());
                queue.ack(envelope.messageId());
                return;
            }
            if (!channelConfigService.isReady(PROVIDER)) {
                log.warn("IM notification skipped channel not ready messageId={} notificationKey={} provider={}",
                        envelope.messageId(), task.notificationKey(), PROVIDER);
                queue.ack(envelope.messageId());
                return;
            }

            ImNotificationMessageContext context = contextResolver.resolve(task);
            String markdown = formatter.format(context, task);
            providerRegistry.require(PROVIDER).send(new ImSendCommand(
                    PROVIDER,
                    identity.getExternalUserId(),
                    messageTitle(task),
                    markdown));
            queue.markDelivered(task.notificationKey());
            queue.ack(envelope.messageId());
        } catch (ImDeliveryException e) {
            SafeImNotificationDeliveryException safe = SafeImNotificationDeliveryException.from(e);
            if (e.isRetryable()) {
                log.error("IM notification delivery retryable failure messageId={} notificationKey={} "
                                + "provider={} deliveryCount={} deliveryRetryable={} providerCode={} "
                                + "providerRequestId={}",
                        envelope.messageId(), task.notificationKey(), PROVIDER, envelope.deliveryCount(),
                        e.isRetryable(), safeValue(e.getProviderCode()),
                        safeValue(e.getProviderRequestId()), safe);
                return;
            }
            log.error("IM notification delivery permanent failure messageId={} notificationKey={} "
                            + "provider={} deliveryRetryable={} providerCode={} providerRequestId={}",
                    envelope.messageId(), task.notificationKey(), PROVIDER, e.isRetryable(),
                    safeValue(e.getProviderCode()), safeValue(e.getProviderRequestId()), safe);
            queue.ack(envelope.messageId());
        } catch (Exception e) {
            AlreadyLoggedException safe = AlreadyLoggedException.from(e);
            log.error("IM notification processing failed messageId={} notificationKey={}",
                    envelope.messageId(), task.notificationKey(), safe);
        }
    }

    private static String safeMessageId(ImNotificationEnvelope envelope) {
        return envelope == null ? "unknown" : envelope.messageId();
    }

    private static String safeValue(String value) {
        return value == null ? "unknown" : value;
    }

    private static String messageTitle(ImNotificationTask task) {
        if (task != null && ImNotificationTask.TYPE_COMMENT_MENTION.equals(task.notificationType())) {
            return "工单评论提醒";
        }
        return "工单指派通知";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
