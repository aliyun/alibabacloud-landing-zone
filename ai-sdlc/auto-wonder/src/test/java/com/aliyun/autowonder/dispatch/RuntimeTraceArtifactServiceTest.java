package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
