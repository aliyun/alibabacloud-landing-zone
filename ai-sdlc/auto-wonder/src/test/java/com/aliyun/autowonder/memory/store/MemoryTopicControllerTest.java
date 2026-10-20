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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void actualTopicHttpRetains400409403AndRawUnknownMessages(String language) throws Exception {
        var service = mock(MemoryTopicService.class);
        var http = MockMvcBuilders.standaloneSetup(new MemoryTopicController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setLocaleResolver(new com.aliyun.autowonder.auth.filter.AuthFilterConfig().localeResolver()).build();
        var context = AutoWonderContext.get(); context.setCurrentWorkspaceId(7L); context.setUserId(9L);
        context.setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN); context.setRequestId("raw-request");
        var validation = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MemoryPathValidator.requireSafe("../raw.md"));
        String raw = "<b>记忆已被修改，请刷新后重试</b>";
        RuntimeException[] errors = {validation, new MemoryDocumentService.MemoryConflictException("raw conflict"),
                new IllegalArgumentException(raw), new MemoryStoreApplicationService.MemoryAccessDeniedException()};
        int[] statuses = {400, 409, 400, 403}; String[] codes = {"10001", "10409", "10001", "10403"};
        String[] messages = language.equals("en-US") ? new String[]{
                "Unsafe memory path: Empty or traversal segments are not allowed", "Memory has changed. Refresh and try again.", raw, "Permission denied"}
                : new String[]{"不安全的记忆路径：不允许空路径段或路径穿越", "记忆已被修改，请刷新后重试", raw, "无权限"};
        try {
            for (int i = 0; i < errors.length; i++) {
                doThrow(errors[i]).when(service).createForUser(eq(7L), eq(9L), eq(true), eq("AGENT"), eq(42L), any());
                http.perform(post("/api/memory-stores/topics").header("Accept-Language", language).contentType("application/json")
                        .content("{\"scope\":\"AGENT\",\"ownerRef\":42,\"topic\":{}}"))
                        .andExpect(status().is(statuses[i])).andExpect(jsonPath("$.code").value(codes[i]))
                        .andExpect(jsonPath("$.message").value(messages[i])).andExpect(jsonPath("$.request_id").value("raw-request"));
            }
        } finally { AutoWonderContext.destroy(); org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

}
