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
        long reportedAt,
        Set<Long> computingConversationTurnIds) implements Serializable {

    public static final String COMPUTE_INVENTORY_FEATURE = "CONVERSATION_COMPUTE_INVENTORY_V1";

    /** Preserve existing callers and serialized legacy snapshots. */
    public ExecutorDispatchSnapshot(String sessionId, int capacity, boolean authoritativeInventory,
            boolean inventoryReady, Set<Long> runningDispatchIds, Set<Long> ownedDispatchIds,
            Set<Long> runningConversationTurnIds, Set<String> protocolFeatures, String inventoryError,
            long reportedAt) {
        this(sessionId, capacity, authoritativeInventory, inventoryReady, runningDispatchIds,
                ownedDispatchIds, runningConversationTurnIds, protocolFeatures, inventoryError, reportedAt, null);
    }

    public ExecutorDispatchSnapshot {
        runningDispatchIds = runningDispatchIds == null ? Set.of() : Set.copyOf(runningDispatchIds);
        ownedDispatchIds = ownedDispatchIds == null ? Set.of() : Set.copyOf(ownedDispatchIds);
        runningConversationTurnIds = runningConversationTurnIds == null
                ? null : Set.copyOf(runningConversationTurnIds);
        protocolFeatures = protocolFeatures == null ? Set.of() : Set.copyOf(protocolFeatures);
        computingConversationTurnIds = computingConversationTurnIds != null
                && computingConversationTurnIds.size() <= 50
                && computingConversationTurnIds.stream().allMatch(id -> id != null && id > 0)
                && runningConversationTurnIds != null
                && runningConversationTurnIds.containsAll(computingConversationTurnIds)
                && protocolFeatures.contains(COMPUTE_INVENTORY_FEATURE)
                ? Set.copyOf(computingConversationTurnIds) : null;
    }

    /** Missing legacy reports are unknown, not evidence of an idle conversation runtime. */
    public boolean hasConversationActivityReport() {
        return runningConversationTurnIds != null;
    }

    public Set<Long> runningConversationTurnIds() {
        return runningConversationTurnIds == null ? Set.of() : runningConversationTurnIds;
    }

    /** Unknown or invalid optional reports conservatively consume ownership capacity. */
    public Set<Long> capacityConversationTurnIds() {
        return computingConversationTurnIds == null ? runningConversationTurnIds() : computingConversationTurnIds;
    }

    public static String key(long executorId) {
        return "exec:dispatch-snapshot:" + executorId;
    }

    public static String closedSessionKey(long executorId, String sessionId) {
        return "exec:closed-session:" + executorId + ":" + sessionId;
    }
}
