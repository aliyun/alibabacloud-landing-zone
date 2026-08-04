package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DaemonCheckpointControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private DispatchDao dispatchDao;
    private DispatchCheckpointService checkpointService;
    private AuditLogService auditLogService;
    private DaemonCheckpointController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        dispatchDao = mock(DispatchDao.class);
        checkpointService = mock(DispatchCheckpointService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new DaemonCheckpointController(authenticator, dispatchDao, checkpointService, auditLogService);
    }

    @Test
    void uploadRecordsAgentAuditLog() throws Exception {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(500L);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(300L);
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        DispatchCheckpointDO stored = new DispatchCheckpointDO();
        stored.setCheckpointSeq(2L);
        stored.setSha256("abc");
        stored.setSizeBytes(3L);
        when(checkpointService.store(eq(dispatch), eq(2L), eq("codex"), eq("session-1"), eq("rt-1"),
                eq("400165"), any(byte[].class))).thenReturn(stored);

        ResponseEntity<?> response = controller.upload(500L, "tok", 2L, "codex", "session-1",
                "rt-1", "400165", new MockMultipartFile("checkpoint", "c.tgz", null, "abc".getBytes()));

        assertEquals(200, response.getStatusCode().value());
        verify(auditLogService).record(argThat(record ->
                record.getTenantId() == 100L
                        && Long.valueOf(300L).equals(record.getActorId())
                        && "AGENT".equals(record.getActorType())
                        && "DISPATCH".equals(record.getModule())
                        && "UPLOAD_CHECKPOINT".equals(record.getAction())
                        && "dispatch".equals(record.getTargetType())
                        && Long.valueOf(500L).equals(record.getTargetId())
                        && "EVENT".equals(record.getTriggerType())
                        && "DAEMON_CALLBACK".equals(record.getTriggerSource())
                        && "daemon.checkpoint".equals(record.getEventType())));
    }

    @Test
    void returns503WhenCheckpointStorageIsUnavailable() throws Exception {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(500L);
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        when(checkpointService.store(eq(dispatch), eq(2L), eq("codex"), eq("session-1"), eq("rt-1"),
                eq("400165"), any(byte[].class))).thenThrow(new RuntimeException("oss unavailable"));

        ResponseEntity<?> response = controller.upload(500L, "tok", 2L, "codex", "session-1",
                "rt-1", "400165", new MockMultipartFile("checkpoint", "c.tgz", null, "abc".getBytes()));

        assertEquals(503, response.getStatusCode().value());
        verifyNoInteractions(auditLogService);
    }
}
