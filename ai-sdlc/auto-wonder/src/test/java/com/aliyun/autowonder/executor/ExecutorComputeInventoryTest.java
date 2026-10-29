package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorComputeInventoryTest {
    private ExecutorDispatchSnapshot snapshot(Set<Long> computing, Set<String> features) {
        return new ExecutorDispatchSnapshot("s", 2, true, true, Set.of(), Set.of(),
                Set.of(10L,20L), features, null, 1L, computing);
    }
    @Test void validSubsetSeparatesComputingFromOwnership() {
        var snapshot = snapshot(Set.of(10L), Set.of("CONVERSATION_COMPUTE_INVENTORY_V1"));
        assertEquals(Set.of(10L), snapshot.capacityConversationTurnIds());
        assertEquals(Set.of(10L,20L), snapshot.runningConversationTurnIds());
        assertEquals(Set.of(), snapshot(Set.of(), Set.of("CONVERSATION_COMPUTE_INVENTORY_V1")).capacityConversationTurnIds());
    }
    @Test void absentInvalidOrUnnegotiatedFallsBackToOwnership() {
        assertEquals(Set.of(10L,20L), snapshot(null, Set.of("CONVERSATION_COMPUTE_INVENTORY_V1")).capacityConversationTurnIds());
        assertEquals(Set.of(10L,20L), snapshot(Set.of(30L), Set.of("CONVERSATION_COMPUTE_INVENTORY_V1")).capacityConversationTurnIds());
        assertEquals(Set.of(10L,20L), snapshot(Set.of(), Set.of("CONVERSATION_COMPUTE_INVENTORY_V2")).capacityConversationTurnIds());
        var legacy = new ExecutorDispatchSnapshot("s", 2, true, true, Set.of(), Set.of(), Set.of(10L), Set.of(), null, 1L);
        assertEquals(Set.of(10L), legacy.capacityConversationTurnIds());
    }
}
