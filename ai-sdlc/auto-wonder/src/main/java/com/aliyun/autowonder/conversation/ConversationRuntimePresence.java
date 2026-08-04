package com.aliyun.autowonder.conversation;

import java.util.Set;

public interface ConversationRuntimePresence {
    boolean isExecutorOnline(long executorId);

    boolean hasConversationTurnActivityReport(long executorId);

    Set<Long> activeConversationTurnIds(long executorId);

    boolean supportsProtocolFeature(long executorId, String feature);
}
