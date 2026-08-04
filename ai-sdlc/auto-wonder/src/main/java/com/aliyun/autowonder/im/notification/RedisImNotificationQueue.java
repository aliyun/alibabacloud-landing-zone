package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.redis.RedisManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import redis.clients.jedis.StreamEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RedisImNotificationQueue implements ImNotificationQueue {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PAYLOAD_FIELD = "payload";
    private static final String DELIVERED_PREFIX = "autowonder:im-notification:delivered:";

    private final RedisManager redisManager;
    private final ImNotificationProperties properties;

    public RedisImNotificationQueue(RedisManager redisManager, ImNotificationProperties properties) {
        this.redisManager = redisManager;
        this.properties = properties;
    }

    @Override
    public void enqueue(ImNotificationTask task) {
        redisManager.ensureConsumerGroup(properties.getStreamKey(), properties.getGroup());
        redisManager.xadd(properties.getStreamKey(),
                Map.of(PAYLOAD_FIELD, serialize(task)),
                properties.getMaxLength());
    }

    @Override
    public List<ImNotificationEnvelope> readNew(String consumer, int count) {
        redisManager.ensureConsumerGroup(properties.getStreamKey(), properties.getGroup());
        return toEnvelopes(redisManager.xreadGroup(
                properties.getStreamKey(),
                properties.getGroup(),
                consumer,
                count,
                properties.getBlockMillis()), false);
    }

    @Override
    public List<ImNotificationEnvelope> claimStale(String consumer, int count) {
        redisManager.ensureConsumerGroup(properties.getStreamKey(), properties.getGroup());
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
    public boolean markDelivered(String notificationKey) {
        return redisManager.setIfAbsent(deliveredKey(notificationKey), "1", properties.getDedupeTtlSeconds());
    }

    @Override
    public boolean isDelivered(String notificationKey) {
        return redisManager.exists(deliveredKey(notificationKey));
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
            ImNotificationTask task = parsePayload(entry.getFields().get(PAYLOAD_FIELD));
            long safeDeliveryCount = Math.max(1L, deliveryCount);
            if (task == null) {
                envelopes.add(ImNotificationEnvelope.invalid(messageId, safeDeliveryCount, "malformed payload"));
            } else {
                envelopes.add(new ImNotificationEnvelope(messageId, task, safeDeliveryCount));
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

    private ImNotificationTask parsePayload(String payload) {
        try {
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return OBJECT_MAPPER.readValue(payload, ImNotificationTask.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String deliveredKey(String notificationKey) {
        return DELIVERED_PREFIX + notificationKey;
    }
}
