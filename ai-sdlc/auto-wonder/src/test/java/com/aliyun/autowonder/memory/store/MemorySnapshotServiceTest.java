package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.store.dto.MemorySnapshotVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemorySnapshotServiceTest {

    @Test
    void composesOrgThenSquadThenPrivateWithoutInliningTopicBodies() {
        MemorySnapshotService service = new MemorySnapshotService();
        MemorySnapshotVO snapshot = service.compose(List.of(
                store(3L, "AGENT", 42L, "- [Private](feedback_private.md) — personal hook", "PRIVATE BODY"),
                store(2L, "SQUAD", 88L, "- [Squad](project_squad.md) — team hook", "SQUAD BODY"),
                store(1L, "ORG", 0L, "- [Org](reference_org.md) — org hook", "ORG BODY")));

        String index = snapshot.composedIndex();
        assertTrue(index.indexOf("team/org/reference_org.md") < index.indexOf("team/squad-88/project_squad.md"));
        assertTrue(index.indexOf("team/squad-88/project_squad.md") < index.indexOf("feedback_private.md"));
        assertFalse(index.contains("ORG BODY"));
        assertFalse(index.contains("SQUAD BODY"));
        assertFalse(index.contains("PRIVATE BODY"));
        assertEquals(List.of("ORG", "SQUAD", "AGENT"),
                snapshot.stores().stream().map(MemorySnapshotVO.Store::scope).toList());
    }

    @Test
    void preservesTheCompleteIndexAndLeavesPromptBoundingToTheRuntime() {
        MemorySnapshotService service = new MemorySnapshotService();
        StringBuilder org = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            org.append("- [Org ").append(i).append("](reference_").append(i).append(".md) — hook\n");
        }
        MemorySnapshotVO snapshot = service.compose(List.of(
                store(1L, "ORG", 0L, org.toString(), "ORG BODY"),
                store(3L, "AGENT", 42L, "- [Private](feedback_private.md) — personal hook", "PRIVATE BODY")));

        String index = snapshot.composedIndex();
        assertTrue(index.contains("<!-- AGENT memory -->"));
        assertTrue(index.contains("feedback_private.md"), "shared memory must not starve personal memory");
        assertTrue(index.contains("reference_199.md"), "snapshot must not discard the authoritative tail");
        assertEquals(200, index.lines().filter(line -> line.startsWith("- [Org ")).count());
        assertEquals(MemoryIndexPolicy.Headroom.OVER_LIMIT, MemoryIndexPolicy.measure(index).headroom());
    }

    @Test
    void emptyPersonalIndexStillHasStableWritableMarker() {
        MemorySnapshotService service = new MemorySnapshotService();
        MemorySnapshotVO snapshot = service.compose(List.of(
                store(1L, "ORG", 0L, "- [Org](reference_org.md) — hook", "ORG BODY"),
                store(3L, "AGENT", 42L, "", "PRIVATE BODY")));

        assertTrue(snapshot.composedIndex().endsWith("<!-- AGENT memory -->\n"));
    }

    private MemorySnapshotVO.Store store(long id, String scope, long owner, String index, String topicBody) {
        return new MemorySnapshotVO.Store(id, scope, owner, 1L, "WRITE", List.of(
                new MemorySnapshotVO.Document("MEMORY.md", index, 1),
                new MemorySnapshotVO.Document(scope.toLowerCase() + "_topic.md", topicBody, 1)));
    }
}
