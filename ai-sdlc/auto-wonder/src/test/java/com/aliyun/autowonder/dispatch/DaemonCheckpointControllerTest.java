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
    void completionReceiptEchoesOnlyPersistedIdentity() throws Exception {
        when(authenticator.authenticate(500L,"tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L,200L,300L));
        DispatchDO dispatch=new DispatchDO(); dispatch.setId(500L);
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        String resultId="b55aa1a7-43d5-4452-b39a-278554b67942";
        DispatchCheckpointDO stored=new DispatchCheckpointDO(); stored.setCheckpointSeq(7L);
        stored.setSha256("abc"); stored.setSizeBytes(3L); stored.setCompletionResultId(resultId);
        when(checkpointService.store(eq(dispatch),eq(7L),isNull(),isNull(),isNull(),isNull(),any(byte[].class),eq(resultId)))
                .thenReturn(stored);
        ResponseEntity<?> response=controller.upload(500,"tok",7,null,null,null,null,
                new MockMultipartFile("checkpoint","abc".getBytes()),"COMPLETION",resultId);
        assertEquals(200,response.getStatusCode().value());
        assertEquals(resultId,((java.util.Map<?,?>)response.getBody()).get("resultId"));
        assertEquals("COMPLETION",((java.util.Map<?,?>)response.getBody()).get("purpose"));
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

    @Test
    void refreshDownloadUrlRejectsUnknownExecutorToken() {
        when(authenticator.authenticate(500L, "bad"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.fail());

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "bad", 1L);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(checkpointService, auditLogService);
    }

    @Test
    void refreshDownloadUrlReturnsNotFoundForUnknownDispatch() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 1L);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("daemon.checkpoint.url.not_found",
                ((java.util.Map<?, ?>) response.getBody()).get("code"));
    }

    @Test
    void refreshDownloadUrlRejectsInvalidCheckpointIdentity() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        when(dispatchDao.findById(500L)).thenReturn(runningDispatch());

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 0L);

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(checkpointService);
    }

    @Test
    void refreshDownloadUrlRejectsDispatchThatIsNoLongerRefreshable() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = runningDispatch();
        dispatch.setStatus(DispatchStatus.SUCCEEDED);
        when(dispatchDao.findById(500L)).thenReturn(dispatch);

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 3L);

        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(checkpointService);
    }

    @Test
    void refreshDownloadUrlMapsMissingObjectToGone() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = runningDispatch();
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        when(checkpointService.refreshDownloadLink(dispatch, 3L))
                .thenThrow(new DispatchCheckpointService.CheckpointObjectMissingException());

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 3L);

        assertEquals(410, response.getStatusCode().value());
        assertEquals("daemon.checkpoint.url.object_missing",
                ((java.util.Map<?, ?>) response.getBody()).get("code"));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void refreshDownloadUrlMapsNonCandidateToForbidden() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = runningDispatch();
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        when(checkpointService.refreshDownloadLink(dispatch, 3L))
                .thenThrow(new DispatchCheckpointService.CheckpointNotRestorableException());

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 3L);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("daemon.checkpoint.url.not_restorable",
                ((java.util.Map<?, ?>) response.getBody()).get("code"));
    }

    @Test
    void refreshDownloadUrlMapsUnknownCheckpointToNotFound() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = runningDispatch();
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        when(checkpointService.refreshDownloadLink(dispatch, 3L))
                .thenThrow(new DispatchCheckpointService.CheckpointNotFoundException());

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 3L);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("daemon.checkpoint.url.not_found",
                ((java.util.Map<?, ?>) response.getBody()).get("code"));
    }

    @Test
    void refreshDownloadUrlMapsServiceInvalidIdentityToBadRequest() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = runningDispatch();
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        when(checkpointService.refreshDownloadLink(dispatch, 3L))
                .thenThrow(new DispatchCheckpointService.CheckpointInvalidRequestException());

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 3L);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("daemon.checkpoint.url.invalid_request",
                ((java.util.Map<?, ?>) response.getBody()).get("code"));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void refreshDownloadUrlReturnsSignedLinkAndRecordsAudit() {
        when(authenticator.authenticate(500L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(100L, 200L, 300L));
        DispatchDO dispatch = runningDispatch();
        when(dispatchDao.findById(500L)).thenReturn(dispatch);
        when(checkpointService.refreshDownloadLink(dispatch, 3L)).thenReturn(
                new CheckpointDownloadLink(3L, "https://oss/cp?Signature=abc",
                        "2026-10-23T10:00:00Z", "sha256:deadbeef", 600));

        ResponseEntity<?> response = controller.refreshDownloadUrl(500L, "tok", 3L);

        assertEquals(200, response.getStatusCode().value());
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
        assertEquals(3L, body.get("checkpointSeq"));
        assertEquals("https://oss/cp?Signature=abc", body.get("downloadUrl"));
        assertEquals("2026-10-23T10:00:00Z", body.get("expiresAt"));
        assertEquals("sha256:deadbeef", body.get("sha256"));
        assertEquals(600, body.get("expiresInSeconds"));
        verify(auditLogService).record(argThat(record ->
                record.getTenantId() == 100L
                        && "REFRESH_CHECKPOINT_URL".equals(record.getAction())
                        && "dispatch".equals(record.getTargetType())
                        && Long.valueOf(500L).equals(record.getTargetId())
                        && "daemon.checkpoint-url".equals(record.getEventType())));
    }

    private static DispatchDO runningDispatch() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(500L);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(300L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        return dispatch;
    }
}
