package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemoryTopicControllerTest {
    @Test void expectedTopicFailuresAreNotSystemErrors() throws Exception {
        var service = mock(MemoryTopicService.class);
        var http = MockMvcBuilders.standaloneSetup(new MemoryTopicController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var context = AutoWonderContext.get();
        context.setCurrentWorkspaceId(7L); context.setUserId(9L);
        context.setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
        try {
            RuntimeException[] errors = {
                new MemoryDocumentService.MemoryConflictException("version changed"),
                new IllegalArgumentException("owner does not exist"),
                new MemoryStoreApplicationService.MemoryAccessDeniedException()
            };
            int[] statuses = {409, 400, 403};
            for (int i = 0; i < errors.length; i++) {
                doThrow(errors[i]).when(service).createForUser(eq(7L), eq(9L), eq(true), eq("AGENT"), eq(42L), any());
                http.perform(post("/api/memory-stores/topics").contentType("application/json")
                        .content("{\"scope\":\"AGENT\",\"ownerRef\":42,\"topic\":{}}"))
                        .andExpect(status().is(statuses[i]))
                        .andExpect(jsonPath("$.success").value(false));
            }
        } finally { AutoWonderContext.destroy(); }
    }
}
