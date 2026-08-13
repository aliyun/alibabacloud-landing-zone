package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.evolution.EvolutionDeltaIngestionLiteService;
import com.aliyun.autowonder.evolution.EvolutionMode;
import com.aliyun.autowonder.evolution.EvolutionModeResolverLiteService;
import com.aliyun.autowonder.memory.MemorySedimentationService;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaemonArtifactControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private ObjectStorage storage;
    private ArtifactService artifactService;
    private MemorySedimentationService memorySedimentation;
    private EvolutionDeltaIngestionLiteService evolutionDeltaIngestion;
    private EvolutionModeResolverLiteService evolutionModeResolver;
    private com.aliyun.autowonder.audit.AuditLogService auditLogService;
    private DispatchAiUsageService usageService;
    private DaemonArtifactController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        storage = mock(ObjectStorage.class);
        artifactService = mock(ArtifactService.class);
        memorySedimentation = mock(MemorySedimentationService.class);
        evolutionDeltaIngestion = mock(EvolutionDeltaIngestionLiteService.class);
        evolutionModeResolver = mock(EvolutionModeResolverLiteService.class);
        auditLogService = mock(com.aliyun.autowonder.audit.AuditLogService.class);
        usageService = mock(DispatchAiUsageService.class);
        lenient().when(evolutionModeResolver.resolve(anyLong(), anyLong())).thenReturn(EvolutionMode.ASSISTED);
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        controller = new DaemonArtifactController(authenticator, storage, artifactService,
                memorySedimentation, evolutionDeltaIngestion, evolutionModeResolver, auditLogService, usageService,
                props);
    }

    @Test
    void returns401WhenAuthFails() throws Exception {
        when(authenticator.authenticate(1L, "bad"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.fail());

        MultipartFile[] files = { new MockMultipartFile("files", "f.txt", null, "hi".getBytes()) };
        ResponseEntity<?> resp = controller.upload(1L, "bad", null, null, files);
        assertEquals(401, resp.getStatusCode().value());
        verifyNoInteractions(storage, artifactService);
    }

    @Test
    void storesFilesAndRecordsArtifacts() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 5L));

        String metadata = "[{\"path\":\"deliverables/report.md\",\"sha256\":\"abc\",\"sizeBytes\":5}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "report.md", null, "hello".getBytes())
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", "key1", metadata, files);
        assertEquals(200, resp.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertTrue(body.get("remoteRef").toString().contains("t/10/workitem/20/dispatch/99/"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals(1, receipts.size());
        assertEquals("deliverables/report.md", receipts.get(0).get("path"));
        assertEquals("ACCEPTED", receipts.get(0).get("status"));

        verify(storage).put(eq("test-artifact-bucket"),
                eq("t/10/workitem/20/dispatch/99/deliverables/report.md"), any());

        ArgumentCaptor<ReportArtifactRequest> cap = ArgumentCaptor.forClass(ReportArtifactRequest.class);
        verify(artifactService).record(cap.capture(), eq(10L));
        assertEquals("deliverables/report.md", cap.getValue().getName());
        assertEquals("DELIVERABLE", cap.getValue().getType());
        assertEquals(99L, cap.getValue().getDispatchId());
        assertEquals(20L, cap.getValue().getWorkitemId());
        verify(auditLogService).record(argThat(record ->
                record.getTenantId() == 10L
                        && Long.valueOf(30L).equals(record.getActorId())
                        && "ARTIFACT".equals(record.getModule())
                        && "UPLOAD_ARTIFACT".equals(record.getAction())));
        verify(usageService).ingestArtifact(eq(10L), eq(20L), eq(99L), any(), eq("deliverables/report.md"), eq("oss://bucket/k"), any(byte[].class));
    }

    @Test
    void storesObservabilityEventsInOssWithoutBackfillingRawPayloads() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/events", "md5", 20L));
        byte[] events = "{\"eventId\":\"99:1\"}\n".getBytes();
        String metadata = "[{\"path\":\"observability/events.jsonl\"}]";

        controller.upload(99L, "tok", "key", metadata,
                new MultipartFile[]{new MockMultipartFile("files", "events.jsonl", null, events)});

        verify(storage).put(eq("test-artifact-bucket"),
                eq("t/10/workitem/20/dispatch/99/observability/events.jsonl"), eq(events));
        ArgumentCaptor<ReportArtifactRequest> cap = ArgumentCaptor.forClass(ReportArtifactRequest.class);
        verify(artifactService).record(cap.capture(), eq(10L));
        assertEquals("TELEMETRY", cap.getValue().getType());
        verify(usageService).ingestArtifact(eq(10L), eq(20L), eq(99L), any(),
                eq("observability/events.jsonl"), eq("oss://bucket/events"), eq(events));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void returns503WhenArtifactStorageIsUnavailable() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("oss unavailable"));

        ResponseEntity<?> response = controller.upload(99L, "tok", "key1", null,
                new MultipartFile[]{new MockMultipartFile("files", "result.md", null, "data".getBytes())});

        assertEquals(503, response.getStatusCode().value());
        verifyNoInteractions(artifactService);
    }

    @Test
    void invokesMemorySedimentationForMemoryDelta() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 100L));

        byte[] content = "{\"entries\":[{\"type\":\"memory\",\"title\":\"t\",\"content\":\"c\"}]}".getBytes();
        String metadata = "[{\"path\":\"learning_delta/memory_delta.json\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "memory_delta.json", null, content)
        };

        controller.upload(99L, "tok", null, metadata, files);

        verify(memorySedimentation).ingest(eq(10L), eq(30L), eq(99L), eq(content));
    }

    @Test
    void invokesEvolutionIngestionForEvolutionDelta() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 100L));

        byte[] content = "{\"candidates\":[{\"assetType\":\"SKILL\",\"assetId\":88}]}".getBytes();
        String metadata = "[{\"path\":\"artifacts/output/learning_delta/evolution_delta.json\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "evolution_delta.json", null, content)
        };

        controller.upload(99L, "tok", null, metadata, files);

        verify(evolutionDeltaIngestion).ingest(eq(10L), eq(30L), eq(99L), eq(content), eq(EvolutionMode.ASSISTED));
    }

    @Test
    void invalidEvolutionDeltaDoesNotRejectArtifactUpload() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 16L));
        when(evolutionDeltaIngestion.ingest(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID));

        byte[] content = "{\"proposals\":[]}".getBytes();
        String metadata = "[{\"path\":\"learning_delta/evolution_delta.json\"}]";
        ResponseEntity<?> response = controller.upload(99L, "tok", null, metadata,
                new MultipartFile[]{new MockMultipartFile("files", "evolution_delta.json", null, content)});

        assertEquals(200, response.getStatusCode().value());
        verify(artifactService).record(any(), eq(10L));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("ACCEPTED", receipts.get(0).get("status"));
    }

    @Test
    void manualEvolutionModeStoresDeltaArtifactsWithoutIngestingThem() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(evolutionModeResolver.resolve(10L, 30L)).thenReturn(EvolutionMode.MANUAL);
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 100L));

        byte[] memory = "{\"entries\":[{\"type\":\"memory\",\"title\":\"t\",\"content\":\"c\"}]}".getBytes();
        byte[] evolution = "{\"candidates\":[{\"assetType\":\"SKILL\",\"assetId\":88}]}".getBytes();
        String metadata = "[{\"path\":\"learning_delta/memory_delta.json\"},{\"path\":\"learning_delta/evolution_delta.json\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "memory_delta.json", null, memory),
                new MockMultipartFile("files", "evolution_delta.json", null, evolution)
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        verify(artifactService, times(2)).record(any(), eq(10L));
        verifyNoInteractions(memorySedimentation, evolutionDeltaIngestion);
    }

    @Test
    void classifiesPathsCorrectly() {
        assertEquals("DELIVERABLE", DaemonArtifactController.classify("deliverables/report.md"));
        assertEquals("PATCH", DaemonArtifactController.classify("patches/fix.patch"));
        assertEquals("EVIDENCE", DaemonArtifactController.classify("evidence/screenshot.png"));
        assertEquals("HANDOFF", DaemonArtifactController.classify("handoff/proposal.json"));
        assertEquals("LEARNING", DaemonArtifactController.classify("learning_delta/memory_delta.json"));
        assertEquals("FILE", DaemonArtifactController.classify("other/something.txt"));
    }

    @Test
    void usesFilenameWhenMetadataMissing() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 3L));

        MultipartFile[] files = {
                new MockMultipartFile("files", "myfile.txt", null, "abc".getBytes())
        };

        controller.upload(99L, "tok", null, null, files);

        verify(storage).put(eq("test-artifact-bucket"),
                eq("t/10/workitem/20/dispatch/99/myfile.txt"), any());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));

        String metadata = "[{\"path\":\"../../etc/passwd\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "passwd", null, "evil".getBytes())
        };

        ResponseEntity<?> response = controller.upload(99L, "tok", null, metadata, files);

        verifyNoInteractions(storage);
        verifyNoInteractions(artifactService);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("REJECTED", receipts.get(0).get("status"));
        assertEquals("INVALID_PATH", receipts.get(0).get("code"));
    }

    @Test
    void sanitizePathRejectsAbsoluteAndTraversal() {
        assertNull(DaemonArtifactController.sanitizePath("../secret"));
        assertNull(DaemonArtifactController.sanitizePath("/etc/passwd"));
        assertNull(DaemonArtifactController.sanitizePath("C:\\temp\\secret.txt"));
        assertNull(DaemonArtifactController.sanitizePath("foo/../../bar"));
        assertNull(DaemonArtifactController.sanitizePath("foo\0bar"));
        assertNull(DaemonArtifactController.sanitizePath(""));
        assertNull(DaemonArtifactController.sanitizePath(null));
        assertEquals("deliverables/report.md", DaemonArtifactController.sanitizePath("deliverables/report.md"));
        assertEquals("artifacts/output/handoff/handoff-to-指派操作人.md",
                DaemonArtifactController.sanitizePath("artifacts/output/handoff/handoff-to-指派操作人.md"));
        assertEquals("file.txt", DaemonArtifactController.sanitizePath("file.txt"));
    }
}
