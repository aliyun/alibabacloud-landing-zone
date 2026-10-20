package com.aliyun.autowonder.conversation;
import org.junit.jupiter.api.Test;
import com.aliyun.autowonder.json.JSON;
import static org.junit.jupiter.api.Assertions.*;
class PlatformEventProjectionTest {
    @Test void stripsInternalThinkingAndNestedSecretsWhileKeepingToolIdentity() {
        String publicPayload=PlatformEventProjection.payload("tool_use", "{\"tool\":\"configure\",\"input\":{\"Authorization\":\"Bearer private\",\"id\":128},\"output\":\"{\\\"token\\\":\\\"private-token\\\"}\"}");
        assertFalse(publicPayload.contains("private"));
        String identity=PlatformEventProjection.payload("tool_use","{\"tool\":\"autowonder.list_scheduled_task_runs\",\"callId\":\"a1c697f0-6072-41d5-a826-75a6513f1be1\"}");
        assertTrue(identity.contains("autowonder.list_scheduled_task_runs"));
        assertTrue(identity.contains("a1c697f0-6072-41d5-a826-75a6513f1be1"));
        assertTrue(publicPayload.contains("configure")); assertTrue(publicPayload.contains("128"));
        assertFalse(PlatformEventProjection.payload("thinking","{\"content\":\"private reasoning\"}").contains("private reasoning"));
    }
    @Test void preservesOriginalReplyContentAndDiagnosticStorage() {
        String raw="{\"content\":\"User-authored external text\"}";
        assertEquals(raw,PlatformEventProjection.payload("text",raw));
        var row=new AgentConversationTurnEventDO();row.setId(1L);row.setConversationId(1L);row.setTurnId(2L);
        row.setDispatchAttempt(1);row.setEventSeq(1L);row.setChunkCount(1);row.setChunkIndex(0);
        row.setEventType("tool_result");row.setPayloadFragment("{\"password\":\"original\"}");
        var projected=PlatformEventProjection.events(java.util.List.of(row));
        assertEquals("{\"password\":\"original\"}",row.getPayloadFragment());
        assertFalse(projected.get(0).getPayloadFragment().contains("original"));
    }
    @Test void assemblesBeforeRedactingAndDropsIncompleteChunks() {
        var first=new AgentConversationTurnEventDO();first.setId(1L);first.setConversationId(1L);first.setTurnId(2L);
        first.setDispatchAttempt(1);first.setEventSeq(1L);first.setChunkCount(2);first.setChunkIndex(0);
        first.setEventType("tool_result");first.setPayloadFragment("{\"token\":\"sec");
        var second=new AgentConversationTurnEventDO();second.setId(2L);second.setConversationId(1L);second.setTurnId(2L);
        second.setDispatchAttempt(1);second.setEventSeq(1L);second.setChunkCount(2);second.setChunkIndex(1);
        second.setEventType("tool_result");second.setPayloadFragment("ret\"}");
        assertTrue(PlatformEventProjection.events(java.util.List.of(first)).isEmpty());
        var full=PlatformEventProjection.events(java.util.List.of(second,first));
        assertEquals(2L,full.get(0).getId());assertEquals("[REDACTED]",JSON.parseObject(full.get(0).getPayloadFragment()).getString("token"));
    }
}
