package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void deniedHumanMutationIsReportedAsForbiddenInsteadOfSystemError() throws Exception {
        MemoryStoreApplicationService service = mock(MemoryStoreApplicationService.class);
        var http = MockMvcBuilders.standaloneSetup(new MemoryStoreController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        AutoWonderContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.READ_WRITE);
        when(service.listForUser(7L, 9L, false)).thenReturn(List.of());
        doThrow(new MemoryStoreApplicationService.MemoryAccessDeniedException())
                .when(service).mutateForUser(eq(7L), eq(9L), eq(false), eq(11L), any());

        http.perform(get("/api/memory-stores"))
                .andExpect(status().isOk());
        http.perform(post("/api/memory-stores/11/mutations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10403"));
    }
}
