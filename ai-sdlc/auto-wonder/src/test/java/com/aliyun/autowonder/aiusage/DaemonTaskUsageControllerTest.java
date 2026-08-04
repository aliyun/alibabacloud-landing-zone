package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.TaskUsageReportRequest;
import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DaemonTaskUsageControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private DispatchAiUsageService usageService;
    private DaemonTaskUsageController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        usageService = mock(DispatchAiUsageService.class);
        controller = new DaemonTaskUsageController(authenticator, usageService);
    }

    @Test
    void acceptsBearerTokenAndRecordsUsage() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", "Bearer tok");
        TaskUsageReportRequest request = new TaskUsageReportRequest();
        TaskUsageReportRequest.TaskUsageEntry entry = new TaskUsageReportRequest.TaskUsageEntry();
        entry.setProvider("codex");
        entry.setModel("gpt-5");
        request.setUsage(List.of(entry));

        ResponseEntity<?> response = controller.reportUsage("99", null, null, request, httpRequest);

        assertEquals(200, response.getStatusCode().value());
        verify(usageService).recordTaskUsage(10L, 99L, request.getUsage());
    }

    @Test
    void acceptsOpaqueTaskIdWithDispatchIdOverride() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        TaskUsageReportRequest request = new TaskUsageReportRequest();
        TaskUsageReportRequest.TaskUsageEntry entry = new TaskUsageReportRequest.TaskUsageEntry();
        entry.setProvider("codex");
        entry.setModel("gpt-5");
        request.setUsage(List.of(entry));

        ResponseEntity<?> response = controller.reportUsage("task-x", "99", "tok", request, new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        verify(usageService).recordTaskUsage(10L, 99L, request.getUsage());
    }

    @Test
    void rejectsInvalidTaskIdWithoutDispatchIdOverride() {
        ResponseEntity<?> response = controller.reportUsage("task-x", null, "tok", new TaskUsageReportRequest(), new MockHttpServletRequest());

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(authenticator, usageService);
    }
}
