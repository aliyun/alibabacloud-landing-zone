package com.aliyun.autowonder.audit;

import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class WebAuditInterceptorTest {

    private static final int MAX_ACTION_CHARS = 64;
    private static final String ELICITATION_REPLY_PATH =
            "/api/workitems/50660/clarification-conversations/87/elicitations/"
                    + "7d474a22899fe700fa199e34f1759e89/reply";

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

    @Test
    void keepsHexIdentifierOutOfActionAndWithinColumnWidth() {
        String action = recordedAction("POST", ELICITATION_REPLY_PATH);

        assertEquals("CREATE_WORKITEMS_REPLY", action);
        assertTrue(action.length() <= MAX_ACTION_CHARS);
        assertFalse(action.contains("D474A22899FE700FA199E34F1759E89"));
    }

    @Test
    void keepsFullRequestPathInDetailWhenActionIsCollapsed() {
        AuditLogRecord record = recordedRequest("POST", ELICITATION_REPLY_PATH);

        assertEquals(ELICITATION_REPLY_PATH, record.getDetail().get("path"));
        assertEquals(50660L, record.getTargetId());
        assertEquals("WORKITEM", record.getModule());
    }

    @Test
    void normalizesDashedUuidWithoutCollapsingShortAction() {
        String action = recordedAction("POST",
                "/api/workitems/42/comments/7d474a22-899f-400f-a199-e34f1759e89/reply");

        assertEquals("CREATE_WORKITEMS_ID_COMMENTS_ID_REPLY", action);
        assertFalse(action.contains("7D474A22"));
    }

    @Test
    void keepsNamingOfPathThatAlreadyFitsColumn() {
        assertEquals("DELETE_WORKITEMS_ID", recordedAction("DELETE", "/api/workitems/42"));
        assertEquals("UPDATE_STATUS_TEMPLATES_ID", recordedAction("PUT", "/api/status-templates/5"));
    }

    @Test
    void auditsAgentEnvironmentVariableBindingsWithoutRequestSecrets() {
        AuditLogRecord mount = recordedRequest("POST", "/api/agents/42/environment-variables/9");
        AuditLogRecord unmount = recordedRequest("DELETE", "/api/agents/42/environment-variables/9");

        assertEquals("CREATE_AGENTS_ID_ENVIRONMENT_VARIABLES_ID", mount.getAction());
        assertEquals("DELETE_AGENTS_ID_ENVIRONMENT_VARIABLES_ID", unmount.getAction());
        assertEquals(42L, mount.getTargetId());
        assertFalse(mount.getDetail().containsKey("body"));
        assertFalse(unmount.getDetail().containsKey("body"));
    }

    @Test
    void doesNotNormalizeShortOrDigitFreeSegments() {
        assertEquals("CREATE_SKILLS_V2_RELEASES", recordedAction("POST", "/api/skills/v2/releases"));
        assertEquals("CREATE_SKILLS_ABCDEFABCDEFABCD_RELEASE",
                recordedAction("POST", "/api/skills/abcdefabcdefabcd/release"));
    }

    @Test
    void toleratesRedundantPathSeparators() {
        assertEquals("CREATE_WORKITEMS_ID_REPLY", recordedAction("POST", "/api/workitems//42/reply"));
    }

    @Test
    void hardTruncatesWhenCollapsedActionStillExceedsColumn() {
        String action = recordedAction("POST", "/api/" + "a".repeat(56) + "/reply");

        assertEquals("CREATE_" + "A".repeat(56), action);
        assertEquals(MAX_ACTION_CHARS - 1, action.length());
        assertFalse(action.endsWith("_"));
    }

    @Test
    void truncatesSingleOverlongSegmentToColumnWidth() {
        String action = recordedAction("POST", "/api/" + "b".repeat(80));

        assertEquals("CREATE_" + "B".repeat(57), action);
        assertEquals(MAX_ACTION_CHARS, action.length());
    }

    private String recordedAction(String method, String path) {
        return recordedRequest(method, path).getAction();
    }

    private AuditLogRecord recordedRequest(String method, String path) {
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebAuditInterceptor interceptor = new WebAuditInterceptor(auditLogService);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/placeholder");
        request.setRequestURI(path);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }
}
