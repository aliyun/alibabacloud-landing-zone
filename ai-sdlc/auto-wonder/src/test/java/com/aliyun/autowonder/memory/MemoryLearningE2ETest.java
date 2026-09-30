package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.memory.store.MemorySnapshotService;
import com.aliyun.autowonder.memory.store.dto.MemorySnapshotVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLearningE2ETest {
    @Test
    void correctedPrivateTopicIsVisibleToTheNextDispatchWithoutReviewOrVersionBinding() {
        MemorySnapshotService snapshots = new MemorySnapshotService();
        String index = "- [Deployment](feedback_deployment.md): verified deployment lane\n";
        MemorySnapshotVO first = snapshots.compose(List.of(store(index, "Use lane A.", 1)));
        assertTrue(first.composedIndex().contains("feedback_deployment.md"));
        assertEquals("Use lane A.", first.stores().get(0).documents().get(1).contentMd());

        // Represents the acknowledged direct document mutation from dispatch 1.
        MemorySnapshotVO second = snapshots.compose(List.of(store(index,
                "Use lane B; verified against current repository config.", 2)));

        assertTrue(second.composedIndex().contains("verified deployment lane"));
        assertEquals("Use lane B; verified against current repository config.",
                second.stores().get(0).documents().get(1).contentMd());
        assertEquals(2, second.stores().get(0).documents().get(1).version());
    }

    private MemorySnapshotVO.Store store(String index, String topic, int version) {
        return new MemorySnapshotVO.Store(3L, "AGENT", 42L, version, "WRITE", List.of(
                new MemorySnapshotVO.Document("MEMORY.md", index, version),
                new MemorySnapshotVO.Document("feedback_deployment.md", topic, version)));
    }
}
