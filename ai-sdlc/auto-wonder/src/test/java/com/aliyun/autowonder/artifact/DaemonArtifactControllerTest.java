package com.aliyun.autowonder.artifact;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.debuglog.DebugLogService;
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
import org.springframework.test.util.ReflectionTestUtils;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaemonArtifactControllerTest {

    @Test
    void reviewPreviouslyAcceptedObjectMustSurviveLaterCanonicalUpload() throws Exception {
        var realStorage = new com.aliyun.autowonder.storage.InMemoryObjectStorage();
        ReflectionTestUtils.setField(controller, "storage", realStorage);
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        String metadata = "[{\"path\":\"artifacts/output/deliverables/report.md\"}]";
        var first = controller.upload(99L, "tok", "10:20:99:1:step:first:1", metadata,
                new MultipartFile[]{new MockMultipartFile("files", "report.md", null, "AAAA".getBytes())});
        var body = (Map<?, ?>) first.getBody();
        var files = (List<?>) body.get("files");
        String acceptedRef = ((Map<?, ?>) files.get(0)).get("ossRef").toString();
        assertEquals(acceptedRef, ((Map<?, ?>) files.get(0)).get("remoteRef"));
        assertArrayEquals("AAAA".getBytes(), realStorage.get(acceptedRef));
        // Later step uploads before the runtime can commit its progress/checkpoint.
        controller.upload(99L, "tok", "10:20:99:1:step:second:1", metadata,
                new MultipartFile[]{new MockMultipartFile("files", "report.md", null, "BBBB".getBytes())});
        assertArrayEquals("AAAA".getBytes(), realStorage.get(acceptedRef),
                "An older checkpoint's accepted object reference must retain its recorded digest");
        var retry = controller.upload(99L, "tok", "retry", metadata,
                new MultipartFile[]{new MockMultipartFile("files", "report.md", null, "AAAA".getBytes())});
        var retryFiles = (List<?>) ((Map<?, ?>) retry.getBody()).get("files");
        assertEquals(acceptedRef, ((Map<?, ?>) retryFiles.get(0)).get("remoteRef"));
    }

    private DaemonUploadAuthenticator authenticator;
    private ObjectStorage storage;
    private ArtifactService artifactService;
    private MemorySedimentationService memorySedimentation;
    private EvolutionDeltaIngestionLiteService evolutionDeltaIngestion;
    private EvolutionModeResolverLiteService evolutionModeResolver;
    private com.aliyun.autowonder.audit.AuditLogService auditLogService;
    private DispatchAiUsageService usageService;
    private DaemonArtifactController controller;
    private ScheduledTaskCapabilityGuard capabilityGuard;

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
        capabilityGuard = mock(ScheduledTaskCapabilityGuard.class);
        lenient().when(evolutionModeResolver.resolve(anyLong(), anyLong())).thenReturn(EvolutionMode.ASSISTED);
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        controller = new DaemonArtifactController(authenticator, storage, artifactService,
                memorySedimentation, evolutionDeltaIngestion, evolutionModeResolver, auditLogService, usageService,
                props);
        ReflectionTestUtils.setField(controller, "capabilityGuard", capabilityGuard);
    }

    @Test
    void scheduledUploadFailsBeforeStorageOrScheduledServicesWhenUnavailable() throws Exception {
        when(authenticator.authenticate(99L, "tok")).thenReturn(
                DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L, null,
                        ExecutionSourceType.SCHEDULED_TASK_RUN));
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("daemon");

        BizException failure = assertThrows(BizException.class, () -> controller.upload(99L, "tok", null,
                null, new MultipartFile[]{new MockMultipartFile("files", "x", null, new byte[]{1})}));

        assertEquals("30006", failure.getCode());
        verifyNoInteractions(storage, artifactService, evolutionModeResolver);
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
                matches("t/10/workitem/20/dispatch/99/objects/sha256/[a-f0-9]{64}/deliverables/report.md"), any());

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
        verifyNoInteractions(capabilityGuard);
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
                matches("t/10/workitem/20/dispatch/99/objects/sha256/[a-f0-9]{64}/observability/events.jsonl"), eq(events));
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
                matches("t/10/workitem/20/dispatch/99/objects/sha256/[a-f0-9]{64}/myfile.txt"), any());
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

    // ---- Task 10：debug/ 中转兜底（classify → DEBUG_LOG、canonical key 重排、RELAY 落库） ----

    @Test
    void classifiesDebugPrefixAsDebugLog() {
        assertEquals("DEBUG_LOG", DaemonArtifactController.classify("debug/DevAgent-99.log.gz"));
        assertTrue(DaemonArtifactController.isDebugLog("debug/DevAgent-99.log.gz"));
        assertTrue(DaemonArtifactController.isDebugLog(
                DaemonArtifactController.logicalPath("artifacts/output/debug/DevAgent-99.log.gz")));
        assertFalse(DaemonArtifactController.isDebugLog("deliverables/debug-notes.md"));
        assertFalse(DaemonArtifactController.isDebugLog(null));
    }

    @Test
    void debugRelayIsStoredUnderCanonicalKeyAndRecordedAsRelayUpload() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DebugLogService debugLogService = mock(DebugLogService.class);
        ReflectionTestUtils.setField(controller, "debugLogService", debugLogService);
        when(debugLogService.relayTarget(10L, 99L))
                .thenReturn(new DebugLogService.RelayTarget("debug/20/DevAgent-run-1.log.gz", 1));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("test-artifact-bucket/debug/20/DevAgent-run-1.log.gz",
                        "md5", 7L));

        String metadata = "[{\"path\":\"debug/DevAgent-99.log.gz\",\"sha256\":\""
                + "a".repeat(64) + "\",\"sizeBytes\":7}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "DevAgent-99.log.gz", null, new byte[7])
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        verify(storage).put(eq("test-artifact-bucket"), eq("debug/20/DevAgent-run-1.log.gz"),
                any(byte[].class));
        ArgumentCaptor<JSONObject> meta = ArgumentCaptor.forClass(JSONObject.class);
        verify(debugLogService).recordRelayUpload(eq(10L), eq(99L),
                eq("debug/20/DevAgent-run-1.log.gz"), eq(1), eq(7L), meta.capture());
        assertEquals("a".repeat(64), meta.getValue().getString("sha256"));
        // DEBUG_LOG 跳过审计（TELEMETRY 先例），但产物行仍按 DEBUG_LOG 类型登记
        verify(auditLogService, never()).record(any());
        ArgumentCaptor<ReportArtifactRequest> artifactCap =
                ArgumentCaptor.forClass(ReportArtifactRequest.class);
        verify(artifactService).record(artifactCap.capture(), eq(10L));
        assertEquals("DEBUG_LOG", artifactCap.getValue().getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("ACCEPTED", receipts.get(0).get("status"));
    }

    @Test
    void debugRelayIsRejectedWhenDispatchHasNoDebugEnabled() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DebugLogService debugLogService = mock(DebugLogService.class);
        ReflectionTestUtils.setField(controller, "debugLogService", debugLogService);
        when(debugLogService.relayTarget(10L, 99L)).thenReturn(null);

        String metadata = "[{\"path\":\"debug/DevAgent-99.log.gz\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "DevAgent-99.log.gz", null, new byte[7])
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        verifyNoInteractions(storage);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("REJECTED", receipts.get(0).get("status"));
        assertEquals("DEBUG_LOG_DISABLED", receipts.get(0).get("code"));
    }

    @Test
    void debugRelayStillRejectsOversizedFiles() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DebugLogService debugLogService = mock(DebugLogService.class);
        ReflectionTestUtils.setField(controller, "debugLogService", debugLogService);

        byte[] oversized = new byte[51 * 1024 * 1024];
        String metadata = "[{\"path\":\"debug/DevAgent-99.log.gz\"}]";
        MultipartFile[] files = { new MockMultipartFile("files", "DevAgent-99.log.gz", null, oversized) };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        verifyNoInteractions(storage);
        // 既有 50MB 单文件上限先于 debug 分支生效：中转前置判定根本不该被触达（S10 Minor#3，
        // 此前该用例桩了 relayTarget 却永不命中，改为显式 verify(never()) 钉住闸门次序）。
        verify(debugLogService, never()).relayTarget(anyLong(), anyLong());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("REJECTED", receipts.get(0).get("status"));
        assertEquals("FILE_TOO_LARGE", receipts.get(0).get("code"));
    }

    /** best-effort 适配：relayTarget 前置判定异常只 REJECT 该 debug 文件，同批其他产物照常入库。 */
    @Test
    void debugRelayLookupFailureRejectsOnlyDebugFileAndKeepsOthers() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DebugLogService debugLogService = mock(DebugLogService.class);
        ReflectionTestUtils.setField(controller, "debugLogService", debugLogService);
        when(debugLogService.relayTarget(10L, 99L)).thenThrow(new RuntimeException("db down"));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("oss://bucket/k", "md5", 5L));

        String metadata = "[{\"path\":\"debug/DevAgent-99.log.gz\"},"
                + "{\"path\":\"deliverables/report.md\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "DevAgent-99.log.gz", null, new byte[7]),
                new MockMultipartFile("files", "report.md", null, "hello".getBytes())
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("REJECTED", receipts.get(0).get("status"));
        assertEquals("DEBUG_LOG_DISABLED", receipts.get(0).get("code"));
        assertEquals("ACCEPTED", receipts.get(1).get("status"));
        verify(storage).put(eq("test-artifact-bucket"),
                matches("t/10/workitem/20/dispatch/99/objects/sha256/[a-f0-9]{64}/deliverables/report.md"), any(byte[].class));
        verify(debugLogService, never()).recordRelayUpload(anyLong(), anyLong(), anyString(),
                anyInt(), anyLong(), any());
    }

    /** best-effort 适配：recordRelayUpload 落库失败不得回滚 ACCEPTED 回执（行由对账/TASK_RESULT 收尾）。 */
    @Test
    void debugRelayRecordFailureKeepsReceiptAccepted() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DebugLogService debugLogService = mock(DebugLogService.class);
        ReflectionTestUtils.setField(controller, "debugLogService", debugLogService);
        when(debugLogService.relayTarget(10L, 99L))
                .thenReturn(new DebugLogService.RelayTarget("debug/20/DevAgent-run-1.log.gz", 1));
        when(storage.put(anyString(), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("test-artifact-bucket/debug/20/DevAgent-run-1.log.gz",
                        "md5", 7L));
        doThrow(new RuntimeException("db down")).when(debugLogService)
                .recordRelayUpload(anyLong(), anyLong(), anyString(), anyInt(), anyLong(), any());

        String metadata = "[{\"path\":\"debug/DevAgent-99.log.gz\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "DevAgent-99.log.gz", null, new byte[7])
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("ACCEPTED", receipts.get(0).get("status"));
        verify(artifactService).record(any(), eq(10L));
    }

    /** 适配：debugLogService bean 缺席（required=false）时 debug/ 文件只 REJECT，不得 500。 */
    @Test
    void debugRelayIsRejectedWhenDebugLogServiceBeanAbsent() throws Exception {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));

        String metadata = "[{\"path\":\"debug/DevAgent-99.log.gz\"}]";
        MultipartFile[] files = {
                new MockMultipartFile("files", "DevAgent-99.log.gz", null, new byte[7])
        };

        ResponseEntity<?> resp = controller.upload(99L, "tok", null, metadata, files);

        assertEquals(200, resp.getStatusCode().value());
        verifyNoInteractions(storage);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("files");
        assertEquals("REJECTED", receipts.get(0).get("status"));
        assertEquals("DEBUG_LOG_DISABLED", receipts.get(0).get("code"));
    }
}
