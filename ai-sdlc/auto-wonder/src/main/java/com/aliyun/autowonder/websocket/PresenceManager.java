package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.conversation.ConversationRuntimePresence;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PresenceManager implements ConversationRuntimePresence {

    private static final Logger log = LoggerFactory.getLogger(PresenceManager.class);
    private static final long TTL_SEC = 90L;
    private static final int LEGACY_DEFAULT_CAPACITY = 3;
    private static final int INVALID_CAPACITY = 1;
    private static final int MAX_CAPACITY = 50;
    private static final String BROADCAST_CHANNEL = "node:dispatch:broadcast";

    private final RedisManager redisManager;
    private final NodeIdentity nodeIdentity;

    public PresenceManager(RedisManager redisManager, NodeIdentity nodeIdentity) {
        this.redisManager = redisManager;
        this.nodeIdentity = nodeIdentity;
    }

    public boolean register(long executorId, long agentId) {
        return register(executorId, agentId, LEGACY_DEFAULT_CAPACITY);
    }

    public boolean register(long executorId, long agentId, int maxConcurrentDispatches) {
        if (redisManager.exists(ExecutorRegistry.deletedKey(executorId))) {
            log.warn("register skipped executor {} is deleted (tombstone present)", executorId);
            return false;
        }
        String nodeId = nodeIdentity.getNodeId();
        int capacity = normalizeCapacity(String.valueOf(maxConcurrentDispatches));
        redisManager.setWithExpire("exec:online:" + executorId, nodeId, TTL_SEC);
        redisManager.setWithExpire("exec:route:" + executorId, nodeId, TTL_SEC);
        redisManager.setWithExpire(capacityKey(executorId), String.valueOf(capacity), TTL_SEC);
        redisManager.sadd("agent:execs:" + agentId, String.valueOf(executorId));
        log.info("presence register executorId={} agentId={} nodeId={} capacity={}",
                executorId, agentId, nodeId, capacity);
        return true;
    }

    public void unregister(long executorId, long agentId) {
        redisManager.del("exec:online:" + executorId);
        redisManager.del("exec:route:" + executorId);
        redisManager.del(capacityKey(executorId));
        redisManager.del(conversationTurnReportKey(executorId));
        redisManager.del(activeConversationTurnKey(executorId));
        redisManager.del(sessionKey(executorId));
        redisManager.del(protocolFeaturesKey(executorId));
        redisManager.srem("agent:execs:" + agentId, String.valueOf(executorId));
        log.info("presence unregister executorId={} agentId={}", executorId, agentId);
    }

    public void announceSession(long executorId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisManager.setWithExpire(sessionKey(executorId), sessionId, TTL_SEC);
        redisManager.publish(BROADCAST_CHANNEL,
                "{\"type\":\"SESSION_REPLACED\",\"executorId\":" + executorId + "}");
    }

    public String currentSessionId(long executorId) {
        return redisManager.getString(sessionKey(executorId));
    }

    public void refreshSession(long executorId, String sessionId) {
        if (isCurrentSession(executorId, sessionId)) {
            redisManager.setWithExpire(sessionKey(executorId), sessionId, TTL_SEC);
        }
    }

    public boolean isCurrentSession(long executorId, String sessionId) {
        return sessionId != null && sessionId.equals(currentSessionId(executorId));
    }

    public boolean heartbeat(long executorId, long agentId, int maxConcurrentDispatches) {
        return register(executorId, agentId, maxConcurrentDispatches);
    }

    public boolean heartbeat(long executorId, long agentId, int maxConcurrentDispatches,
            Collection<Long> activeConversationTurnIds) {
        return heartbeat(executorId, agentId, maxConcurrentDispatches,
                activeConversationTurnIds, null);
    }

    public boolean heartbeat(long executorId, long agentId, int maxConcurrentDispatches,
            Collection<Long> activeConversationTurnIds, Collection<String> protocolFeatures) {
        if (!register(executorId, agentId, maxConcurrentDispatches)) {
            return false;
        }
        recordActiveConversationTurns(executorId, activeConversationTurnIds);
        recordProtocolFeatures(executorId, protocolFeatures);
        return true;
    }

    private void recordProtocolFeatures(long executorId, Collection<String> protocolFeatures) {
        String key = protocolFeaturesKey(executorId);
        redisManager.del(key);
        if (protocolFeatures != null && !protocolFeatures.isEmpty()) {
            protocolFeatures.forEach(f -> redisManager.sadd(key, f));
            redisManager.setExpire(key, TTL_SEC);
        }
    }

    private void recordActiveConversationTurns(long executorId,
            Collection<Long> activeConversationTurnIds) {
        String activeKey = activeConversationTurnKey(executorId);
        redisManager.del(activeKey);
        if (activeConversationTurnIds != null) {
            activeConversationTurnIds.stream()
                    .filter(id -> id != null && id > 0)
                    .map(String::valueOf)
                    .forEach(id -> redisManager.sadd(activeKey, id));
            if (!activeConversationTurnIds.isEmpty()) {
                redisManager.setExpire(activeKey, TTL_SEC);
            }
        }
        redisManager.setWithExpire(conversationTurnReportKey(executorId), "1", TTL_SEC);
    }

    public boolean isExecutorOnline(long executorId) {
        return redisManager.exists("exec:online:" + executorId);
    }

    @Override
    public boolean hasConversationTurnActivityReport(long executorId) {
        return redisManager.exists(conversationTurnReportKey(executorId));
    }

    @Override
    public Set<Long> activeConversationTurnIds(long executorId) {
        Set<String> raw = redisManager.smembers(activeConversationTurnKey(executorId));
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        return raw.stream().map(PresenceManager::parseLongOrNull)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
    }

    public int capacity(long executorId) {
        return normalizeCapacity(redisManager.getString(capacityKey(executorId)));
    }

    static String capacityKey(long executorId) {
        return "exec:capacity:" + executorId;
    }

    static String conversationTurnReportKey(long executorId) {
        return "exec:conversation-turn-report:" + executorId;
    }

    static String activeConversationTurnKey(long executorId) {
        return "exec:conversation-turns:" + executorId;
    }

    static String sessionKey(long executorId) {
        return "exec:session:" + executorId;
    }

    @Override
    public boolean supportsProtocolFeature(long executorId, String feature) {
        Set<String> features = redisManager.smembers(protocolFeaturesKey(executorId));
        return features != null && features.contains(feature);
    }

    static String protocolFeaturesKey(long executorId) {
        return "exec:protocol-features:" + executorId;
    }

    private static Long parseLongOrNull(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static int normalizeCapacity(String raw) {
        if (raw == null) {
            return LEGACY_DEFAULT_CAPACITY;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed <= 0) {
                return INVALID_CAPACITY;
            }
            return Math.min(parsed, MAX_CAPACITY);
        } catch (NumberFormatException ignored) {
            return INVALID_CAPACITY;
        }
    }
}
