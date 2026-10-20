package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeTraceArtifactServiceTest {

    @Test
    void readsCanonicalTurnObservationAndContextFromTenantOssArtifacts() {
        ArtifactDao artifactDao = mock(ArtifactDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ArtifactDO traceArtifact = artifact(1L, 44L, "observability/trace.json", "bucket/trace");
        ArtifactDO contextArtifact = artifact(1L, 44L, "observability/context/files/abc", "bucket/context");
        when(artifactDao.listByDispatch(1L, 44L)).thenReturn(List.of(traceArtifact, contextArtifact));
        when(storage.get("bucket/trace")).thenReturn(("""
                {"schemaVersion":"autowonder.runtime-trace.v2","dispatchId":"44","sessions":[{
                  "sessionId":"qoder-session","provider":"qoder","turns":[{
                    "traceId":"turn-1","systemPrompt":"full system prompt","prompt":"full user prompt",
                    "contextFiles":[{"role":"CONTEXT","name":"issue_context.md","contentRef":"context/files/abc","previewable":true}],
                    "observations":[{"observationId":"turn-1:agent","type":"AGENT","name":"qoder turn","children":[{
                      "observationId":"call-1","parentObservationId":"turn-1:agent","type":"MCP","name":"code.search",
                      "input":{"query":"posterior"},"output":"result payload","durationMs":283
                    }]}]
                  }]
                }]}
                """).getBytes(StandardCharsets.UTF_8));
        when(storage.get("bucket/context")).thenReturn("# Issue\n".getBytes(StandardCharsets.UTF_8));

        RuntimeTraceArtifactService service = new RuntimeTraceArtifactService(artifactDao, storage);

        RuntimeTraceVO.Turn turn = service.loadTurn(1L, 44L, "turn-1");
        RuntimeTraceVO.Observation observation = service.loadObservation(1L, 44L, "call-1");
        RuntimeTraceArtifactService.ContextContent content = service.loadContext(1L, 44L, "context/files/abc");

        assertEquals("full system prompt", turn.getSystemPrompt());
        assertEquals("posterior", ((java.util.Map<?, ?>) observation.getInput()).get("query"));
        assertEquals("result payload", observation.getOutput());
        assertEquals("# Issue\n", new String(content.bytes(), StandardCharsets.UTF_8));

        RuntimeTraceVO outline = service.loadOutline(1L, 44L);
        RuntimeTraceVO.Turn outlinedTurn = outline.getSessions().get(0).getTurns().get(0);
        assertNull(outlinedTurn.getPrompt());
        assertNull(outlinedTurn.getSystemPrompt());
        assertNull(outlinedTurn.getObservations().get(0).getChildren().get(0).getInput());
        assertNull(outlinedTurn.getObservations().get(0).getChildren().get(0).getOutput());
        assertEquals("turn-1", outlinedTurn.getTraceId());
    }

    @Test
    void rejectsContextTraversal() {
        RuntimeTraceArtifactService service = new RuntimeTraceArtifactService(mock(ArtifactDao.class), mock(ObjectStorage.class));
        assertThrows(IllegalArgumentException.class, () -> service.loadContext(1L, 44L, "../trace.json"));
    }

    private static ArtifactDO artifact(long tenantId, long dispatchId, String name, String ossRef) {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setTenantId(tenantId);
        artifact.setDispatchId(dispatchId);
        artifact.setName(name);
        artifact.setOssRef(ossRef);
        return artifact;
    }
    @Test
    void archivedOutlineKeepsTerminalFailureHistoryWhileStrippingTurnPayloads() {
        ArtifactDao artifactDao = mock(ArtifactDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ArtifactDO traceArtifact = artifact(1L, 55L, "observability/trace.json", "bucket/trace");
        when(artifactDao.listByDispatch(1L, 55L)).thenReturn(List.of(traceArtifact));
        when(storage.get("bucket/trace")).thenReturn(("""
                {"schemaVersion":"autowonder.runtime-trace.v2","dispatchId":"55","sessions":[{
                  "sessionId":"qoder-session","turns":[{
                    "traceId":"turn-1","systemPrompt":"full system prompt","prompt":"full user prompt","output":"final answer"
                  }]
                }],"events":[{
                  "eventId":"55:9","eventType":"dispatch.failed","eventTime":"2026-10-15T03:41:07.331Z",
                  "detail":{"state":"FAILED","error":"workflow routing preflight failed: qoder semantic inactivity timeout after 10m0s",
                    "failureHistory":{"truncated":false,"rounds":[{
                      "phase":"routing_preflight","attemptIndex":1,"sessionId":"689186f5-a6bd-4816-9fa1-6bff68e7b896",
                      "status":"failed","error":"qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed",
                      "diagnostics":[{"source":"provider_log","category":"business","code":"10605",
                        "subCode":"model_queued","message":"Queuing failed",
                        "requestId":"f203f510-c793-4e82-b64d-18c879abb08c",
                        "serverRequestId":"e0a61fb9-725f-4b21-b9ac-ce708c58c054",
                        "retryable":true,"willRetry":true,"occurrences":2}]},
                      {"phase":"routing_preflight","attemptIndex":2,"sessionId":"aa2db1e7-4256-49eb-ae7b-6faea387b7e0",
                      "status":"failed","error":"qoder first payload timeout after 10m0s",
                      "diagnostics":[{"source":"provider_rpc","category":"timeout","code":"timeout",
                        "message":"no first payload within 10m0s",
                        "requestId":"a3203242-026f-43ab-b92b-1ccc700155c4",
                        "serverRequestId":"345785b7-b5b3-4c9f-b250-a0bd39f770b3"}]}]}}}]}
                """).getBytes(StandardCharsets.UTF_8));

        RuntimeTraceVO outline = new RuntimeTraceArtifactService(artifactDao, storage).loadOutline(1L, 55L);

        assertEquals("OSS", outline.getSource());
        RuntimeTraceVO.Event terminal = outline.getEvents().get(0);
        assertEquals("dispatch.failed", terminal.getEventType());
        assertEquals("workflow routing preflight failed: qoder semantic inactivity timeout after 10m0s",
                terminal.getDetail().get("error"));
        Object history = terminal.getDetail().get("failureHistory");
        assertInstanceOf(java.util.Map.class, history);
        java.util.List<?> rounds = (java.util.List<?>) ((java.util.Map<?, ?>) history).get("rounds");
        assertEquals(2, rounds.size());
        java.util.Map<?, ?> firstRound = (java.util.Map<?, ?>) rounds.get(0);
        assertEquals("routing_preflight", firstRound.get("phase"));
        assertEquals("qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed", firstRound.get("error"));
        java.util.List<?> diagnostics = (java.util.List<?>) firstRound.get("diagnostics");
        java.util.Map<?, ?> firstDiagnostic = (java.util.Map<?, ?>) diagnostics.get(0);
        assertEquals("10605", String.valueOf(firstDiagnostic.get("code")));
        assertEquals("model_queued", firstDiagnostic.get("subCode"));
        assertEquals("f203f510-c793-4e82-b64d-18c879abb08c", firstDiagnostic.get("requestId"));
        assertEquals("e0a61fb9-725f-4b21-b9ac-ce708c58c054", firstDiagnostic.get("serverRequestId"));
        assertEquals(2, firstDiagnostic.get("occurrences"));
        java.util.Map<?, ?> secondRound = (java.util.Map<?, ?>) rounds.get(1);
        assertTrue(String.valueOf(secondRound.get("error")).contains("timeout"));

        RuntimeTraceVO.Turn turn = outline.getSessions().get(0).getTurns().get(0);
        assertNull(turn.getPrompt());
        assertNull(turn.getSystemPrompt());
        assertNull(turn.getOutput());
    }

    @Test
    void invalidContextRefKeepsIllegalArgumentTypeAndRawDiagnosticAtBilingualBoundary() {
        RuntimeTraceArtifactService service = new RuntimeTraceArtifactService(mock(ArtifactDao.class), mock(ObjectStorage.class));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.loadContext(1L,44L,"../trace.json"));
        assertEquals("invalid context content ref", ex.getMessage());
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleIllegalArg(ex);
            assertEquals("The context content reference is invalid.", response.getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous); }
    }
}
