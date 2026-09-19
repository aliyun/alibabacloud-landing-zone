package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.util.MessageDigestUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import redis.clients.jedis.StreamEntry;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisImNotificationQueue implements ImNotificationQueue {
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    private static final String PAYLOAD_FIELD = "payload";
    private static final String DELIVERED_PREFIX = "autowonder:im-notification:delivered:";

    private final RedisManager redisManager;
    private final ImNotificationProperties properties;
    private volatile boolean consumerGroupEnsured;

    public RedisImNotificationQueue(RedisManager redisManager, ImNotificationProperties properties) {
        this.redisManager = redisManager;
        this.properties = properties;
    }

    @Override
    public void enqueue(ImNotificationTask task) {
        ensureConsumerGroupOnce();
        redisManager.xadd(properties.getStreamKey(),
                Map.of(PAYLOAD_FIELD, serialize(task)),
                properties.getMaxLength());
    }

    @Override
    public List<ImNotificationEnvelope> readNew(String consumer, int count) {
        ensureConsumerGroupOnce();
        return toEnvelopes(redisManager.xreadGroup(
                properties.getStreamKey(),
                properties.getGroup(),
                consumer,
                count,
                properties.getBlockMillis()), false);
    }

    @Override
    public List<ImNotificationEnvelope> claimStale(String consumer, int count) {
        ensureConsumerGroupOnce();
        return toEnvelopes(redisManager.xautoClaim(
                properties.getStreamKey(),
                properties.getGroup(),
                consumer,
                properties.getClaimIdleMs(),
                count), true);
    }

    @Override
    public void ack(String messageId) {
        redisManager.xack(properties.getStreamKey(), properties.getGroup(), messageId);
    }

    @Override
    public void sendToDlq(ImNotificationEnvelope envelope, String reason) {
        String payload = envelope.payload();
        if (payload == null && envelope.task() != null) {
            payload = serialize(envelope.task());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("messageId", envelope.messageId());
        fields.put("dropReason", reason == null || reason.isBlank() ? "unknown" : reason);
        fields.put("deliveryCount", String.valueOf(envelope.deliveryCount()));
        fields.put("droppedAt", String.valueOf(System.currentTimeMillis()));
        fields.put(PAYLOAD_FIELD, payload == null ? "" : payload);
        redisManager.xadd(properties.getDlqStreamKey(), fields, properties.getDlqMaxLength());
    }

    @Override
    public boolean markDelivered(String notificationKey) {
        return redisManager.setIfAbsent(deliveredKey(notificationKey), "1", properties.getDedupeTtlSeconds());
    }

    @Override
    public boolean isDelivered(String notificationKey) {
        return redisManager.exists(deliveredKey(notificationKey));
    }

    private void ensureConsumerGroupOnce() {
        // Skip xgroupCreate once the group is known to exist: creating on every poll hammered Redis
        // during outages and wasted one connection round-trip per read.
        if (!consumerGroupEnsured) {
            redisManager.ensureConsumerGroup(properties.getStreamKey(), properties.getGroup());
            consumerGroupEnsured = true;
        }
    }

    private List<ImNotificationEnvelope> toEnvelopes(List<StreamEntry> entries, boolean loadPendingCount) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ImNotificationEnvelope> envelopes = new ArrayList<>(entries.size());
        for (StreamEntry entry : entries) {
            String messageId = entry.getID().toString();
            long deliveryCount = loadPendingCount
                    ? redisManager.xpendingDeliveryCount(properties.getStreamKey(), properties.getGroup(), messageId)
                    : 1L;
            String payload = entry.getFields().get(PAYLOAD_FIELD);
            ParsedPayload parsed = parsePayload(payload);
            long safeDeliveryCount = Math.max(1L, deliveryCount);
            if (parsed.task() == null) {
                envelopes.add(ImNotificationEnvelope.invalid(
                        messageId, safeDeliveryCount, parsed.reason(), parsed.summary(), payload));
            } else {
                envelopes.add(new ImNotificationEnvelope(messageId, parsed.task(), safeDeliveryCount, payload));
            }
        }
        return envelopes;
    }

    private String serialize(ImNotificationTask task) {
        try {
            return OBJECT_MAPPER.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("IM notification payload serialization failed", e);
        }
    }

    private ParsedPayload parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return new ParsedPayload(null, "malformed payload: missing payload", payloadSummary(payload));
        }
        try {
            return new ParsedPayload(OBJECT_MAPPER.readValue(payload, ImNotificationTask.class), null, null);
        } catch (JsonProcessingException e) {
            // Jackson messages may echo payload tokens, so only the exception type reaches logs;
            // the full payload is retained in the DLQ for audit and manual resend.
            return new ParsedPayload(null,
                    "malformed payload: " + e.getClass().getSimpleName(),
                    payloadSummary(payload));
        }
    }

    static String payloadSummary(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "len=0";
        }
        try {
            return "len=" + payload.length()
                    + " md5=" + MessageDigestUtil.getMD5(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return "len=" + payload.length();
        }
    }

    private record ParsedPayload(ImNotificationTask task, String reason, String summary) {
    }

    private static String deliveredKey(String notificationKey) {
        return DELIVERED_PREFIX + notificationKey;
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }
}
