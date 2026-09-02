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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class ImNotificationWorker implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ImNotificationWorker.class);
    private static final String PROVIDER = "DINGTALK";

    private final ImNotificationQueue queue;
    private final ImNotificationFormatter formatter;
    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImProviderRegistry providerRegistry;
    private final ImNotificationProperties properties;
    private final ImNotificationMessageContextResolver contextResolver;
    private final ExecutorService sendExecutor;
    private final Semaphore sendPermits;

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
        int concurrency = properties.getSendConcurrency();
        this.sendPermits = new Semaphore(concurrency);
        this.sendExecutor = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "im-send");
            t.setDaemon(true);
            return t;
        });
    }

    public void pollNew() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("IM notification poll new failed", e);
        }
    }

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

    void processAll(List<ImNotificationEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return;
        }
        if (envelopes.size() == 1) {
            processWithBoundary(envelopes.get(0));
            return;
        }
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(envelopes.size());
        for (ImNotificationEnvelope envelope : envelopes) {
            try {
                sendPermits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("IM notification batch interrupted while waiting for send permit");
                break;
            }
            futures.add(sendExecutor.submit(() -> {
                try {
                    processWithBoundary(envelope);
                } finally {
                    sendPermits.release();
                }
            }));
        }
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException e) {
                log.error("IM notification batch task failed", e.getCause());
            }
        }
    }

    private void processWithBoundary(ImNotificationEnvelope envelope) {
        try {
            process(envelope);
        } catch (Exception e) {
            log.error("IM notification message processing boundary failed messageId={}",
                    safeMessageId(envelope), e);
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

        long dequeuedAtMs = System.currentTimeMillis();
        long enqueueToDequeueMs = computeQueueLatencyMs(envelope.messageId(), dequeuedAtMs);
        log.info("IM notification dequeued messageId={} notificationKey={} requestId={} "
                        + "deliveryCount={} queueLatencyMs={}",
                envelope.messageId(), task.notificationKey(), safeValue(task.requestId()),
                envelope.deliveryCount(), enqueueToDequeueMs);

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

            long providerStartMs = System.currentTimeMillis();
            providerRegistry.require(PROVIDER).send(new ImSendCommand(
                    PROVIDER,
                    identity.getExternalUserId(),
                    messageTitle(task),
                    markdown));
            long providerLatencyMs = System.currentTimeMillis() - providerStartMs;

            queue.markDelivered(task.notificationKey());
            queue.ack(envelope.messageId());
            log.info("IM notification delivered messageId={} notificationKey={} requestId={} "
                            + "provider={} providerLatencyMs={} queueLatencyMs={}",
                    envelope.messageId(), task.notificationKey(), safeValue(task.requestId()),
                    PROVIDER, providerLatencyMs, enqueueToDequeueMs);
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

    private static long computeQueueLatencyMs(String messageId, long nowMs) {
        if (messageId == null || messageId.isBlank()) {
            return -1L;
        }
        int dash = messageId.indexOf('-');
        if (dash <= 0) {
            return -1L;
        }
        try {
            long streamTimestampMs = Long.parseLong(messageId.substring(0, dash));
            return nowMs - streamTimestampMs;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String safeMessageId(ImNotificationEnvelope envelope) {
        return envelope == null ? "unknown" : envelope.messageId();
    }

    private static String safeValue(String value) {
        return value == null ? "unknown" : value;
    }

    private static String messageTitle(ImNotificationTask task) {
        String notificationType = task == null ? null : task.notificationType();
        if (ImNotificationTask.TYPE_COMMENT_MENTION.equals(notificationType)) {
            return "工单评论提醒";
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST.equals(notificationType)) {
            return "权限申请通知";
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED.equals(notificationType)) {
            return "权限申请审批结果";
        }
        return "工单指派通知";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public void destroy() {
        sendExecutor.shutdown();
        try {
            if (!sendExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                sendExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sendExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
