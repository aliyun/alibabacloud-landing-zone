package com.aliyun.autowonder.executor;

import java.io.Serializable;
import java.util.Set;

/** One heartbeat's dispatch inventory, fenced to a single WebSocket session. */
public record ExecutorDispatchSnapshot(
        String sessionId,
        int capacity,
        boolean authoritativeInventory,
        boolean inventoryReady,
        Set<Long> runningDispatchIds,
        Set<Long> ownedDispatchIds,
        Set<Long> runningConversationTurnIds,
        Set<String> protocolFeatures,
        String inventoryError,
        long reportedAt) implements Serializable {

    public ExecutorDispatchSnapshot {
        runningDispatchIds = runningDispatchIds == null ? Set.of() : Set.copyOf(runningDispatchIds);
        ownedDispatchIds = ownedDispatchIds == null ? Set.of() : Set.copyOf(ownedDispatchIds);
        runningConversationTurnIds = runningConversationTurnIds == null
                ? null : Set.copyOf(runningConversationTurnIds);
        protocolFeatures = protocolFeatures == null ? Set.of() : Set.copyOf(protocolFeatures);
    }

    /** Missing legacy reports are unknown, not evidence of an idle conversation runtime. */
    public boolean hasConversationActivityReport() {
        return runningConversationTurnIds != null;
    }

    public Set<Long> runningConversationTurnIds() {
        return runningConversationTurnIds == null ? Set.of() : runningConversationTurnIds;
    }

    public static String key(long executorId) {
        return "exec:dispatch-snapshot:" + executorId;
    }

    public static String closedSessionKey(long executorId, String sessionId) {
        return "exec:closed-session:" + executorId + ":" + sessionId;
    }
}
