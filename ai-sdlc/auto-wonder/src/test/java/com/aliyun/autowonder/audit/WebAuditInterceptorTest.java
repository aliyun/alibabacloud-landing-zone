package com.aliyun.autowonder.audit;

import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class WebAuditInterceptorTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void recordsAuthenticatedHumanMutation() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebAuditInterceptor interceptor = new WebAuditInterceptor(auditLogService);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/workitems/42/content");
        request.setQueryString("source=console");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(auditLogService).record(argThat(record -> {
            assertEquals(100L, record.getTenantId());
            assertEquals(7L, record.getActorId());
            assertEquals("HUMAN", record.getActorType());
            assertEquals("WORKITEM", record.getModule());
            assertEquals("UPDATE_WORKITEMS_ID_CONTENT", record.getAction());
            assertEquals("workitem", record.getTargetType());
            assertEquals(42L, record.getTargetId());
            assertEquals("ACTIVE", record.getTriggerType());
            assertEquals("USER_CLICK", record.getTriggerSource());
            assertEquals("http.put", record.getEventType());
            return true;
        }));
    }

    @Test
    void skipsReadAndDaemonEndpoints() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebAuditInterceptor interceptor = new WebAuditInterceptor(auditLogService);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);

        interceptor.afterCompletion(new MockHttpServletRequest("GET", "/api/workitems/42"),
                new MockHttpServletResponse(), new Object(), null);
        interceptor.afterCompletion(new MockHttpServletRequest("POST", "/api/daemon/dispatches/1/comments"),
                new MockHttpServletResponse(), new Object(), null);

        verifyNoInteractions(auditLogService);
    }
}
