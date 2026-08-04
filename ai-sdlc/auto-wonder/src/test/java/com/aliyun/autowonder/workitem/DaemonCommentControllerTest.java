package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DaemonCommentControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private WorkitemService workitemService;
    private AuditLogService auditLogService;
    private GuidanceService guidanceService;
    private DaemonCommentController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        workitemService = mock(WorkitemService.class);
        auditLogService = mock(AuditLogService.class);
        guidanceService = mock(GuidanceService.class);
        controller = new DaemonCommentController(authenticator, workitemService, auditLogService, guidanceService);
    }

    @Test
    void commentRecordsAgentAuditLog() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        CommentVO comment = new CommentVO();
        comment.setId(700L);
        when(workitemService.addAgentComment(200L, "done", 100L, 300L)).thenReturn(comment);

        ResponseEntity<?> response = controller.comment(500L, "tok", Map.of("contentMd", "done"));

        assertEquals(200, response.getStatusCode().value());
        verify(guidanceService).createForComment(100L, 200L, 700L, "done", null, 300L);
        verify(auditLogService).record(argThat(record ->
                record.getTenantId() == 100L
                        && Long.valueOf(300L).equals(record.getActorId())
                        && "AGENT".equals(record.getActorType())
                        && "WORKITEM".equals(record.getModule())
                        && "CREATE_WORKITEM_COMMENT".equals(record.getAction())
                        && "workitem".equals(record.getTargetType())
                        && Long.valueOf(200L).equals(record.getTargetId())
                        && "EVENT".equals(record.getTriggerType())
                        && "DAEMON_CALLBACK".equals(record.getTriggerSource())
                        && "daemon.comment".equals(record.getEventType())));
    }

    @Test
    void workitemStatusRecordsAgentAuditLog() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        when(workitemService.agentTransition(200L, "verifying", 100L, 300L)).thenReturn(new com.aliyun.autowonder.workitem.dto.WorkitemVO());

        ResponseEntity<?> response = controller.workitemStatus(500L, "tok", Map.of("status", "verifying"));

        assertEquals(200, response.getStatusCode().value());
        verify(auditLogService).record(argThat(record ->
                "UPDATE_WORKITEM_STATUS".equals(record.getAction())
                        && "workitem".equals(record.getTargetType())
                        && Long.valueOf(200L).equals(record.getTargetId())
                        && "daemon.workitem-status".equals(record.getEventType())));
    }

    @Test
    void interactionDispatchRejectsDirectCommentAndStatusMutations() {
        for (String mode : new String[]{"COMMENT_INTERACTION", "SIDE_INTERACTION", "CANONICAL_INTERACTION"}) {
            reset(authenticator, workitemService, auditLogService, guidanceService);
            when(authenticator.authenticate(500L, "tok"))
                    .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L, mode));

            ResponseEntity<?> commentResponse = controller.comment(500L, "tok", Map.of("contentMd", "duplicate"));
            ResponseEntity<?> statusResponse = controller.workitemStatus(500L, "tok", Map.of("status", "done"));

            assertEquals(409, commentResponse.getStatusCode().value(), mode);
            assertEquals(409, statusResponse.getStatusCode().value(), mode);
            verifyNoInteractions(workitemService, auditLogService, guidanceService);
        }
    }
}
