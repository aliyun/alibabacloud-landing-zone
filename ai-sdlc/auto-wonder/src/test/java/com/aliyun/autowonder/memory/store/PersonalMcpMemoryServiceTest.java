package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalMcpMemoryServiceTest {
    private final MemoryDirectoryService directory = mock(MemoryDirectoryService.class);
    private final MemoryStoreApplicationService stores = mock(MemoryStoreApplicationService.class);
    private final MemoryDocumentDao documents = mock(MemoryDocumentDao.class);
    private final MemoryTopicService topics = mock(MemoryTopicService.class);
    private final PersonalMcpMemoryService service = new PersonalMcpMemoryService(directory, stores, documents, topics);

    private MemoryDirectoryService.Owner owner(long storeId, String scope, long ref) {
        return new MemoryDirectoryService.Owner(scope, ref, "owner", List.of(), storeId, "READ", 1L, false);
    }
    private MemoryDocumentDO document(long id, long storeId, String path) {
        var doc = new MemoryDocumentDO(); doc.setId(id); doc.setStoreId(storeId); doc.setTenantId(7L);
        doc.setPath(path); doc.setVersion(3); doc.setContentMd("---\ntype: reference\ntitle: Raw title\ndescription: lookup\n---\n\nRaw body\n");
        return doc;
    }
    @Test
    void searchUsesOnlyOwnersVisibleOnPageAndKeepsMetadataAndIndexReadable() {
        var topic = document(1L, 10L, "topic.md"); var index = document(2L, 10L, "MEMORY.md");
        index.setContentMd("[Raw title](topic.md)");
        when(directory.list(7L, 9L, false)).thenReturn(new MemoryDirectoryService.Directory(List.of(owner(10L, "AGENT", 42L)), null));
        when(stores.documentsForUser(7L, 9L, false, 10L)).thenReturn(List.of(topic, index));
        var result = service.search(7L, 9L, false, null, null, null, null, 1, 20);
        assertEquals(List.of("MEMORY.md", "topic.md"), result.stream().map(PersonalMcpMemoryService.MemoryView::path).toList());
        assertEquals("Raw title", result.get(1).title()); assertEquals("\nRaw body\n", result.get(1).body());
        assertEquals(42L, result.get(1).ownerRef()); assertEquals(3, result.get(1).version());
        assertEquals(1, service.search(7L, 9L, false, "raw body", "AGENT", 42L, "reference", 1, 20).size());
        assertTrue(service.search(7L, 9L, false, null, "ORG", null, null, 1, 20).isEmpty());
        assertTrue(service.search(7L, 9L, false, null, null, null, null, 2, 20).isEmpty());
    }
    @Test
    void getRechecksUserDocumentPermissionsInsteadOfReadingDaoContentDirectly() {
        var doc = document(1L, 10L, "topic.md");
        when(documents.findById(7L, 1L)).thenReturn(doc);
        when(directory.list(7L, 9L, false)).thenReturn(new MemoryDirectoryService.Directory(List.of(owner(10L,"ORG",0L)), null));
        when(stores.documentsForUser(7L, 9L, false, 10L)).thenReturn(List.of(doc));
        assertEquals(1L, service.get(7L, 9L, false, 1L).id());
        when(stores.documentsForUser(7L, 9L, false, 10L)).thenReturn(List.of());
        assertThrows(BizException.class, () -> service.get(7L, 9L, false, 1L));
        verify(stores, times(2)).documentsForUser(7L, 9L, false, 10L);
    }
    @Test
    void foreignDeletedAndOwnersMissingFromPageAreNotReadable() {
        assertThrows(BizException.class, () -> service.get(7L, 9L, false, 99L));
        var deleted = document(2L, 10L, "gone.md"); deleted.setDeletedAt(new Date());
        when(documents.findById(7L, 2L)).thenReturn(deleted);
        assertThrows(BizException.class, () -> service.get(7L, 9L, false, 2L));
        when(documents.findById(7L, 1L)).thenReturn(document(1L, 10L,"topic.md"));
        when(directory.list(7L, 9L, false)).thenReturn(new MemoryDirectoryService.Directory(List.of(),null));
        assertThrows(BizException.class, () -> service.get(7L, 9L, false, 1L));
        verifyNoInteractions(stores);
    }
    @Test
    void editsUsePageTopicWorkflowAndCannotMoveOrRenameTopics() {
        var doc = document(1L, 10L, "topic.md");
        when(documents.findById(7L, 1L)).thenReturn(doc);
        when(directory.list(7L, 9L, false)).thenReturn(new MemoryDirectoryService.Directory(List.of(owner(10L,"ORG",0L)), null));
        doc.setDeletedAt(new Date()); // A lost successful update can replay after another user deletes it.
        when(topics.updateForUser(eq(7L), eq(9L), eq(false), eq(10L), any())).thenReturn(doc);
        service.update(7L, 9L, false, 1L, new com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest(
                "moved.md", "reference", "New title", "Lookup", "Body", 3, "edit-key"));
        verify(topics).updateForUser(eq(7L), eq(9L), eq(false), eq(10L), argThat(r ->
                "topic.md".equals(r.path()) && r.expectedVersion() == 3 && "edit-key".equals(r.idempotencyKey())));
        verify(stores, never()).mutateForUser(anyLong(), anyLong(), anyBoolean(), anyLong(), any());
    }

    @Test
    void indexEditingUsesUserMutationAndDeletionReplaysTombstonesThroughTopicService() {
        var index = document(2L, 10L, "MEMORY.md");
        when(documents.findById(7L, 2L)).thenReturn(index);
        when(directory.list(7L, 9L, true)).thenReturn(new MemoryDirectoryService.Directory(List.of(owner(10L,"ORG",0L)), null));
        when(stores.documentsForUser(7L, 9L, true, 10L)).thenReturn(List.of(index));
        when(stores.mutateForUser(eq(7L), eq(9L), eq(true), eq(10L), any())).thenReturn(index);
        service.update(7L, 9L, true, 2L, new com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest(
                null, null, null, null, "Raw index", 3, "index-key"));
        verify(stores).mutateForUser(eq(7L), eq(9L), eq(true), eq(10L), argThat(r ->
                "MEMORY.md".equals(r.path()) && "Raw index".equals(r.contentMd()) && r.expectedVersion() == 3));
        var deleted = document(1L, 10L, "topic.md"); deleted.setDeletedAt(new Date());
        when(documents.findById(7L, 1L)).thenReturn(deleted);
        service.delete(7L, 9L, true, 1L, 3, "delete-key");
        verify(topics).deleteForUser(7L, 9L, true, 10L, "topic.md", 3, "delete-key");
        verify(topics, never()).updateForUser(anyLong(), anyLong(), anyBoolean(), anyLong(), any());
    }

}
