package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class MemoryStoreControllerTest {
    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void delegatesBrowseMutationAclAndHistoryWithWorkspaceIdentity() {
        MemoryStoreApplicationService service = mock(MemoryStoreApplicationService.class);
        MemoryStoreController controller = new MemoryStoreController(service);
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        AutoWonderContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
        List<MemoryStoreDO> stores = List.of(new MemoryStoreDO());
        when(service.listForUser(7L, 9L, true)).thenReturn(stores);

        assertSame(stores, controller.list().getData());
        MemoryMutationRequest mutation = MemoryMutationRequest.create("feedback.md", "body", "request-1");
        controller.mutate(11L, mutation);
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        controller.putAcl(11L, acl);
        controller.deleteAcl(11L, 15L);
        controller.history(11L, 0L, 100);

        verify(service).mutateForUser(7L, 9L, true, 11L, mutation);
        verify(service).putAcl(7L, 9L, true, 11L, acl);
        verify(service).deleteAcl(7L, 9L, true, 11L, 15L);
        verify(service).historyForUser(7L, 9L, true, 11L, 0L, 100);
    }
}
