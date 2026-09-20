package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.conversation.ConversationRuntimePresence;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class PresenceManager implements ConversationRuntimePresence {

    private static final Logger log = LoggerFactory.getLogger(PresenceManager.class);
    private static final long TTL_SEC = 90L;
    private static final int LEGACY_DEFAULT_CAPACITY = 3;
    private static final int INVALID_CAPACITY = 1;
    private static final int MAX_CAPACITY = 50;
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final String BROADCAST_CHANNEL = "node:dispatch:broadcast";

    private final RedisManager redisManager;
    private final NodeIdentity nodeIdentity;

    public PresenceManager(RedisManager redisManager, NodeIdentity nodeIdentity) {
        this.redisManager = redisManager;
        this.nodeIdentity = nodeIdentity;
    }

    public enum SessionMutationResult {
        APPLIED,
        STALE_SESSION,
        DELETED,
        RETRY
    }

    public void unregister(long executorId, long agentId) {
        redisManager.del("exec:online:" + executorId);
        redisManager.del("exec:route:" + executorId);
        redisManager.del(capacityKey(executorId));
        redisManager.del(conversationTurnReportKey(executorId));
        redisManager.del(activeConversationTurnKey(executorId));
        redisManager.del(sessionKey(executorId));
        redisManager.del(protocolFeaturesKey(executorId));
        redisManager.del(ExecutorDispatchSnapshot.key(executorId));
        redisManager.del(versionKey(executorId));
        redisManager.del(modelKey(executorId));
        redisManager.srem("agent:execs:" + agentId, String.valueOf(executorId));
        log.info("presence unregister executorId={} agentId={}", executorId, agentId);
    }

    public boolean recordProtocolError(long executorId, long agentId, String sessionId, String error) {
        if (!isCurrentSession(executorId, sessionId)) return false;
        redisManager.setWithExpire(protocolErrorKey(executorId, sessionId), error, TTL_SEC);
        redisManager.sadd("agent:execs:" + agentId, String.valueOf(executorId));
        return true;
    }

    public boolean clearProtocolError(long executorId, long agentId, String sessionId) {
        if (!isCurrentSession(executorId, sessionId)) return false;
        redisManager.del(protocolErrorKey(executorId, sessionId));
        return true;
    }

    public String currentProtocolError(long executorId) {
        String sessionId = currentSessionId(executorId);
        return sessionId == null ? null
                : redisManager.getString(protocolErrorKey(executorId, sessionId));
    }

    public String currentAgentProtocolError(long agentId) {
        Set<String> executors = redisManager.smembers("agent:execs:" + agentId);
        if (executors == null) return null;
        for (String raw : executors) {
            try {
                String error = currentProtocolError(Long.parseLong(raw));
                if (error != null && !error.isBlank()) return error;
            } catch (NumberFormatException ignored) {
                // Ignore stale malformed membership.
            }
        }
        return null;
    }

    private static String protocolErrorKey(long executorId) {
        return "exec:protocol-error:" + executorId;
    }

    private static String protocolErrorKey(long executorId, String sessionId) {
        return protocolErrorKey(executorId) + ":" + sessionId;
    }

    /** Records the daemon-reported runtime version; heartbeats renew the TTL like other presence keys. */
    public void recordVersion(long executorId, String version) {
        if (version == null || version.isBlank()) {
            return;
        }
        String trimmed = version.trim();
        if (trimmed.length() > MAX_VERSION_LENGTH) {
            trimmed = trimmed.substring(0, MAX_VERSION_LENGTH);
        }
        redisManager.setWithExpire(versionKey(executorId), trimmed, TTL_SEC);
    }

    /** Latest reported runtime version, or null when the executor never reported one (older client). */
    public String currentVersion(long executorId) {
        return redisManager.getString(versionKey(executorId));
    }

    /** Records the daemon-reported effective model id; heartbeats renew the TTL like other presence keys. */
    public void recordModel(long executorId, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String trimmed = model.trim();
        if (trimmed.length() > MAX_MODEL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_MODEL_LENGTH);
        }
        redisManager.setWithExpire(modelKey(executorId), trimmed, TTL_SEC);
    }

    /** Latest reported effective model id, or null when the executor never reported one (older client). */
    public String currentModel(long executorId) {
        return redisManager.getString(modelKey(executorId));
    }

    public boolean announceSession(long executorId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // Session ownership is replaced only when a new connection is authenticated.
        // Heartbeats must never be able to write an older session id back.
        redisManager.setString(sessionKey(executorId), sessionId);
        redisManager.publish(BROADCAST_CHANNEL,
                "{\"type\":\"SESSION_REPLACED\",\"executorId\":" + executorId + "}");
        return true;
    }

    public SessionMutationResult publishHeartbeat(long executorId, long agentId,
            String sessionId, ExecutorDispatchSnapshot snapshot,
            Collection<String> protocolFeatures, String version, String model) {
        if (sessionId == null || snapshot == null || !sessionId.equals(snapshot.sessionId())) {
            return SessionMutationResult.STALE_SESSION;
        }
        if (redisManager.exists(ExecutorRegistry.deletedKey(executorId))) {
            return SessionMutationResult.DELETED;
        }
        if (!isCurrentSession(executorId, sessionId)) {
            return SessionMutationResult.STALE_SESSION;
        }
        if (redisManager.exists(ExecutorDispatchSnapshot.closedSessionKey(executorId, sessionId))) {
            return SessionMutationResult.STALE_SESSION;
        }
        List<String> features = protocolFeatures == null ? List.of() : protocolFeatures.stream()
                .filter(java.util.Objects::nonNull).filter(feature -> !feature.isBlank())
                .distinct().sorted().toList();
        List<Long> conversations = snapshot.runningConversationTurnIds().stream()
                .filter(id -> id != null && id > 0).sorted().toList();
        ExecutorDispatchSnapshot storedSnapshot = new ExecutorDispatchSnapshot(
                snapshot.sessionId(), snapshot.capacity(), snapshot.authoritativeInventory(),
                snapshot.inventoryReady(), snapshot.runningDispatchIds(), snapshot.ownedDispatchIds(),
                snapshot.hasConversationActivityReport() ? snapshot.runningConversationTurnIds() : null,
                Set.copyOf(features), snapshot.inventoryError(),
                snapshot.reportedAt());
        if (!redisManager.set(ExecutorDispatchSnapshot.key(executorId), storedSnapshot,
                (int) TTL_SEC)) {
            return SessionMutationResult.RETRY;
        }
        if (snapshot.hasConversationActivityReport()) {
            redisManager.replaceSetWithExpire(activeConversationTurnKey(executorId),
                    conversations.stream().map(String::valueOf).toList(), TTL_SEC);
            redisManager.setWithExpire(conversationTurnReportKey(executorId), "1", TTL_SEC);
        }
        redisManager.replaceSetWithExpire(protocolFeaturesKey(executorId), features, TTL_SEC);
        recordVersion(executorId, version);
        recordModel(executorId, model);
        redisManager.setWithExpire(capacityKey(executorId),
                String.valueOf(normalizeCapacity(String.valueOf(snapshot.capacity()))), TTL_SEC);
        redisManager.setWithExpire("exec:online:" + executorId, nodeIdentity.getNodeId(), TTL_SEC);
        redisManager.setWithExpire("exec:route:" + executorId, nodeIdentity.getNodeId(), TTL_SEC);
        redisManager.sadd("agent:execs:" + agentId, String.valueOf(executorId));
        clearProtocolError(executorId, agentId, sessionId);
        return SessionMutationResult.APPLIED;
    }

    public boolean unregisterIfCurrent(long executorId, long agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        redisManager.setWithExpire(ExecutorDispatchSnapshot.closedSessionKey(executorId, sessionId),
                "1", TTL_SEC);
        // A session-scoped marker cannot delete replacement presence. Explicit
        // executor deletion still calls unregister().
        return isCurrentSession(executorId, sessionId);
    }

    public String currentSessionId(long executorId) {
        return redisManager.getString(sessionKey(executorId));
    }

    public boolean isCurrentSession(long executorId, String sessionId) {
        return sessionId != null && sessionId.equals(currentSessionId(executorId));
    }

    public boolean isExecutorOnline(long executorId) {
        return redisManager.exists("exec:online:" + executorId)
                && currentDispatchSnapshot(executorId) != null;
    }

    @Override
    public boolean hasConversationTurnActivityReport(long executorId) {
        ExecutorDispatchSnapshot snapshot = currentDispatchSnapshot(executorId);
        return snapshot != null && snapshot.hasConversationActivityReport();
    }

    @Override
    public Set<Long> activeConversationTurnIds(long executorId) {
        ExecutorDispatchSnapshot snapshot = currentDispatchSnapshot(executorId);
        if (snapshot != null) return snapshot.runningConversationTurnIds();
        return Collections.emptySet();
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
        ExecutorDispatchSnapshot snapshot = currentDispatchSnapshot(executorId);
        return snapshot != null && snapshot.protocolFeatures().contains(feature);
    }

    private ExecutorDispatchSnapshot currentDispatchSnapshot(long executorId) {
        try {
            Object value = redisManager.get(ExecutorDispatchSnapshot.key(executorId));
            if (!(value instanceof ExecutorDispatchSnapshot snapshot)) return null;
            String sessionId = currentSessionId(executorId);
            return sessionId != null && sessionId.equals(snapshot.sessionId())
                    && !redisManager.exists(ExecutorDispatchSnapshot.closedSessionKey(
                            executorId, sessionId)) ? snapshot : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String protocolFeaturesKey(long executorId) {
        return "exec:protocol-features:" + executorId;
    }

    static String versionKey(long executorId) {
        return "exec:version:" + executorId;
    }

    static String modelKey(long executorId) {
        return "exec:model:" + executorId;
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
