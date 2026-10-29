package com.aliyun.autowonder.conversation;

import java.util.Set;

public interface ConversationRuntimePresence {
    boolean isExecutorOnline(long executorId);

    boolean hasConversationTurnActivityReport(long executorId);

    Set<Long> activeConversationTurnIds(long executorId);

    /** A single session-fenced inventory newer than the stop/dispatch boundary. Legacy is unknown. */
    default boolean isConversationTurnInactiveAfter(long executorId, long turnId, long boundaryMillis) {
        return false;
    }

    boolean supportsProtocolFeature(long executorId, String feature);
}
