package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryLearningServerPathTest {
    @Test
    void acknowledgedCorrectionIsVisibleToAnotherDispatch() {
        MemoryStoreDao stores = mock(MemoryStoreDao.class);
        MemoryDocumentDao documents = mock(MemoryDocumentDao.class);
        MemoryChangeDao changes = mock(MemoryChangeDao.class);
        MemoryStoreAclDao acls = mock(MemoryStoreAclDao.class);
        SquadMemberDao squads = mock(SquadMemberDao.class);

        MemoryStoreDO store = new MemoryStoreDO();
        store.setId(3L);
        store.setTenantId(7L);
        store.setScope("AGENT");
        store.setOwnerRef(42L);
        store.setStatus("ACTIVE");
        store.setCurrentRevision(1L);
        store.setVersion(1);
        Map<String, MemoryDocumentDO> state = new LinkedHashMap<>();
        state.put("MEMORY.md", document(10L, "MEMORY.md",
                "- [Deployment](deployment-preference.md) — verified deployment lane\n", 1));
        state.put("deployment-preference.md", document(11L, "deployment-preference.md",
                topic("A"), 1));

        when(stores.findById(7L, 3L)).thenReturn(store);
        when(stores.findActive(7L, "AGENT", 42L)).thenReturn(store);
        when(stores.listActive(7L)).thenAnswer(ignored -> List.of(store));
        when(stores.advanceRevision(eq(7L), eq(3L), anyInt(), anyLong())).thenReturn(1);
        when(documents.findByPath(eq(7L), eq(3L), anyString(), anyBoolean()))
                .thenAnswer(invocation -> state.get(invocation.getArgument(2)));
        when(documents.findById(eq(7L), anyLong())).thenAnswer(invocation -> state.values().stream()
                .filter(document -> document.getId().equals(invocation.getArgument(1))).findFirst().orElse(null));
        when(documents.listLive(7L, 3L)).thenAnswer(ignored -> new ArrayList<>(state.values()));
        when(documents.update(anyLong(), eq(7L), eq(3L), anyString(), anyString(), anyString(), anyLong(),
                anyInt(), anyLong())).thenAnswer(invocation -> {
            MemoryDocumentDO document = state.values().stream()
                    .filter(value -> value.getId().equals(invocation.getArgument(0))).findFirst().orElseThrow();
            document.setPath(invocation.getArgument(3));
            document.setContentMd(invocation.getArgument(4));
            document.setContentSha256(invocation.getArgument(5));
            document.setByteSize(invocation.getArgument(6));
            document.setVersion((Integer) invocation.getArgument(7) + 1);
            return 1;
        });
        when(acls.listByStore(7L, 3L)).thenReturn(List.of());
        when(squads.listByAgent(42L)).thenReturn(List.of());

        MemoryDocumentService documentService = new MemoryDocumentService(stores, documents, changes);
        MemoryStoreApplicationService application = new MemoryStoreApplicationService(
                stores, documents, changes, acls, squads, new MemoryStoreAccessService(),
                documentService, new MemorySnapshotService(), new MemoryStoreBootstrapService(stores, documents));
        var firstDispatch = principal(100L);
        MemoryDocumentDO ack = application.mutateForAgent(firstDispatch, 3L,
                MemoryMutationRequest.update("deployment-preference.md", topic("B"), 1,
                        "dispatch:100:correction"));

        assertEquals(2, ack.getVersion());
        assertEquals(2L, store.getCurrentRevision());
        verify(changes).insert(argThat(change -> change.getDispatchId() == 100L
                && "dispatch:100:correction".equals(change.getIdempotencyKey())));

        var secondDispatch = principal(101L);
        var snapshot = application.snapshotForAgent(secondDispatch);
        String recalled = snapshot.stores().get(0).documents().stream()
                .filter(document -> "deployment-preference.md".equals(document.path()))
                .findFirst().orElseThrow().contentMd();
        assertTrue(recalled.contains("Preferred lane: B"));
        assertFalse(recalled.contains("Preferred lane: A"));
    }

    private static DispatchMcpTokenService.DispatchPrincipal principal(long dispatchId) {
        return new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, dispatchId, 42L, WorkspaceAccessLevel.READ_WRITE);
    }

    private static MemoryDocumentDO document(long id, String path, String content, int version) {
        MemoryDocumentDO document = new MemoryDocumentDO();
        document.setId(id);
        document.setTenantId(7L);
        document.setStoreId(3L);
        document.setPath(path);
        document.setContentMd(content);
        document.setVersion(version);
        return document;
    }

    private static String topic(String lane) {
        return "---\nname: deployment-preference\ndescription: verified deployment lane\nmetadata:\n"
                + "  type: feedback\n---\n\nPreferred lane: " + lane + ".\n";
    }
}
