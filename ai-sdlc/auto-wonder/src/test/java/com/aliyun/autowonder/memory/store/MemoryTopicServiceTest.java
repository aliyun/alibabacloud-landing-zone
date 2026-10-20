package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import com.aliyun.autowonder.squad.SquadDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MemoryTopicServiceTest {
    private final MemoryStoreDao stores = mock(MemoryStoreDao.class);
    private final MemoryDocumentDao documents = mock(MemoryDocumentDao.class);
    private final MemoryChangeDao changes = mock(MemoryChangeDao.class);
    private final MemoryStoreAclDao acls = mock(MemoryStoreAclDao.class);
    private final MemoryDocumentService mutations = mock(MemoryDocumentService.class);
    private final MemoryTopicService service = new MemoryTopicService(stores, documents, changes, acls,
            mutations, new MemoryStoreAccessService(), mock(AgentDao.class), mock(SquadDao.class));

    @Test
    void baselineMembersCannotCreateUpdateOrDeleteTopics() {
        when(stores.findActive(7L, "ORG", 0L)).thenReturn(store(13L, "ORG", 0L));
        when(stores.lockById(7L, 13L)).thenReturn(store(13L, "ORG", 0L));
        when(acls.listByStore(7L, 13L)).thenReturn(List.of());
        MemoryTopicRequest topic = new MemoryTopicRequest("topic.md", "feedback", "标题", "说明", "正文", 1, "key-1");

        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.createForUser(7L, 9L, false, "ORG", 0L, topic));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.updateForUser(7L, 9L, false, 13L, topic));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.deleteForUser(7L, 9L, false, 13L, "topic.md", 1, "key-2"));
        verifyNoInteractions(mutations);
    }

    @Test
    void aclWriteGrantRestoresTopicEditingOnlyForTheGrantedStore() {
        when(stores.lockById(7L, 11L)).thenReturn(store(11L, "SQUAD", 8L));
        when(stores.lockById(7L, 12L)).thenReturn(store(12L, "SQUAD", 9L));
        when(acls.listByStore(7L, 11L)).thenReturn(List.of(acl(11L, "USER", "9", "WRITE")));
        when(acls.listByStore(7L, 12L)).thenReturn(List.of());

        assertThrows(KeyedIllegalArgumentException.class,
                () -> service.deleteForUser(7L, 9L, false, 11L, "topic.md", 1, "key-3"));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.deleteForUser(7L, 9L, false, 12L, "topic.md", 1, "key-4"));
    }

    @Test
    void archivedStoresRejectMembersBeforeAnyPermissionCheck() {
        MemoryStoreDO archived = store(14L, "SQUAD", 8L);
        archived.setStatus("ARCHIVED");
        when(stores.lockById(7L, 14L)).thenReturn(archived);
        when(acls.listByStore(7L, 14L)).thenReturn(List.of(acl(14L, "USER", "9", "WRITE")));

        assertThrows(KeyedIllegalArgumentException.class,
                () -> service.deleteForUser(7L, 9L, false, 14L, "topic.md", 1, "key-5"));
        verify(acls, never()).listByStore(7L, 14L);
        verifyNoInteractions(mutations);
    }

    private MemoryStoreDO store(long id, String scope, long ownerRef) {
        MemoryStoreDO store = new MemoryStoreDO();
        store.setId(id);
        store.setTenantId(7L);
        store.setScope(scope);
        store.setOwnerRef(ownerRef);
        store.setStatus("ACTIVE");
        store.setVersion(0);
        return store;
    }

    private MemoryStoreAclDO acl(long storeId, String type, String ref, String permission) {
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        acl.setTenantId(7L);
        acl.setStoreId(storeId);
        acl.setSubjectType(type);
        acl.setSubjectRef(ref);
        acl.setPermission(permission);
        return acl;
    }
}
