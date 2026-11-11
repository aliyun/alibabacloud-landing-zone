package com.aliyun.autowonder.conversation;

import java.util.Comparator;
import java.util.List;

/** Derives the active turn from the same ID-ordered rows exposed as conversation history. */
final class ConversationTurnSnapshot {
    private ConversationTurnSnapshot() {}

    static AgentConversationTurnDO activeInbound(List<AgentConversationTurnDO> turns) {
        return turns.stream()
                .filter(t -> "IN".equals(t.getDirection()) && "PROCESSING".equals(t.getStatus()))
                .findFirst()
                .orElseGet(() -> turns.stream()
                        .filter(t -> "IN".equals(t.getDirection()) && "QUEUED".equals(t.getStatus()))
                        .min(Comparator.comparing(AgentConversationTurnDO::getGmtCreate,
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(AgentConversationTurnDO::getId))
                        .orElse(null));
    }
}
