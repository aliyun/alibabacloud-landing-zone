package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.skill.RuntimeMcpConnectionTestService;
import javax.websocket.Session;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class InboundFrameRouterConversationTest {

    private static String heartbeat(String conversationIds) {
        return "{\"type\":\"HEARTBEAT\",\"maxConcurrentDispatches\":1,"
                + "\"protocolFeatures\":[\"dispatch_inventory_v1\"],"
                + "\"runningDispatchIds\":[],\"ownedDispatchIds\":[],"
                + "\"runningConversationTurnIds\":" + conversationIds + ","
                + "\"dispatchInventoryReady\":true}";
    }

    private final DispatchService dispatchService = mock(DispatchService.class);
    private final ArtifactService artifactService = mock(ArtifactService.class);
    private final PresenceManager presenceManager = mock(PresenceManager.class);
    private final HandoffService handoffService = mock(HandoffService.class);
    private final DispatchDrainScheduler drainScheduler = mock(DispatchDrainScheduler.class);
    private final DispatchPauseService pauseService = mock(DispatchPauseService.class);
    private final GuidanceService guidanceService = mock(GuidanceService.class);
    private final InteractionWorkflowService interactionWorkflowService = mock(InteractionWorkflowService.class);
    private final AgentConversationService convSvc = mock(AgentConversationService.class);
    private final RuntimeMcpConnectionTestService runtimeMcpConnectionTestService =
            mock(RuntimeMcpConnectionTestService.class);

    private final InboundFrameRouter router = new InboundFrameRouter(dispatchService, artifactService,
            presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
            interactionWorkflowService, convSvc, null, runtimeMcpConnectionTestService);

    @Test
    void doesNotConfirmWhenTerminalPersistenceFails() throws Exception {
        ExecutorSession es = mock(ExecutorSession.class);
        when(es.getTenantId()).thenReturn(1L); when(es.getExecutorId()).thenReturn(9L);
        doThrow(new IllegalStateException("db unavailable")).when(convSvc).acknowledgeTurn(
                eq(1L), eq(9L), eq(77L), eq(55L), any(), any(), any(), any(), any(), eq(2));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> router.route(es,
                "{\"type\":\"CONVERSATION_TURN_ACK\",\"conversationId\":77,\"turnId\":55,"
                + "\"dispatchAttempt\":2,\"conversationDeliveryProtocolVersion\":1,\"status\":\"SUCCESS\"}"));
        verify(es, never()).sendText(anyString());
    }

    @Test
    void confirmsNegotiatedTerminalDeliveryWithExactIdentity() throws Exception {
        ExecutorSession es = mock(ExecutorSession.class);
        when(es.getTenantId()).thenReturn(1L); when(es.getExecutorId()).thenReturn(9L);
        String terminal = "{\"type\":\"CONVERSATION_TURN_ACK\",\"conversationId\":77,\"turnId\":55,"
                + "\"dispatchAttempt\":2,\"conversationDeliveryProtocolVersion\":1,\"status\":\"SUCCESS\"}";
        router.route(es, terminal);
        router.route(es, terminal);
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(es, times(2)).sendText(sent.capture());
        assertEquals(sent.getAllValues().get(0), sent.getAllValues().get(1));
        verify(convSvc, times(2)).acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, null, null, null, 2);
        var ack = com.aliyun.autowonder.json.JSON.parseObject(sent.getAllValues().get(0));
        assertEquals("CONVERSATION_DELIVERY_ACK", ack.getString("type"));
        assertEquals("CONVERSATION_TURN_ACK", ack.getString("frameType"));
        assertEquals(2, ack.getIntValue("dispatchAttempt"));
        assertEquals(55L, ack.getLongValue("turnId"));
        assertEquals(77L, ack.getLongValue("conversationId"));
        assertEquals(0L, ack.getLongValue("eventSeq"));
        assertEquals(0, ack.getIntValue("chunkIndex"));
    }

    @Test
    void routesConversationTurnAckToService() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        String frame = "{\"type\":\"CONVERSATION_TURN_ACK\",\"conversationId\":77,\"turnId\":55,"
                + "\"status\":\"SUCCESS\",\"replyMarkdown\":\"hi\",\"sessionId\":\"s1\"}";

        router.route(es, frame);

        verify(convSvc).acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "hi", "s1", null);
    }

    @Test
    void parsesConversationTurnUsageFromAck() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        String frame = "{\"type\":\"CONVERSATION_TURN_ACK\",\"conversationId\":77,\"turnId\":55,"
                + "\"status\":\"SUCCESS\",\"replyMarkdown\":\"hi\",\"sessionId\":\"s1\","
                + "\"usage\":{\"provider\":\"qoder\",\"model\":\"ultimate\",\"inputTokens\":1234,"
                + "\"outputTokens\":56,\"cacheReadTokens\":7,\"cacheWriteTokens\":8,"
                + "\"reasoningTokens\":9,\"credits\":0.3512}}";

        router.route(es, frame);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.aliyun.autowonder.aiusage.dto.ConversationTurnUsage.class);
        verify(convSvc).acknowledgeTurn(eq(1L), eq(9L), eq(77L), eq(55L), eq("SUCCESS"),
                isNull(), eq("hi"), eq("s1"), captor.capture());
        var usage = captor.getValue();
        assertEquals("qoder", usage.getProvider());
        assertEquals("ultimate", usage.getModel());
        assertEquals(1234L, usage.getInputTokens());
        assertEquals(56L, usage.getOutputTokens());
        assertEquals(7L, usage.getCacheReadTokens());
        assertEquals(8L, usage.getCacheWriteTokens());
        assertEquals(9L, usage.getReasoningTokens());
        assertEquals(0, new java.math.BigDecimal("0.3512").compareTo(usage.getCredits()));
    }

    @Test
    void heartbeatRecoversStaleConversationTurnsForExecutor() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(PresenceManager.SessionMutationResult.APPLIED);

        router.route(es, heartbeat("[55]"));

        verify(convSvc).recoverStaleTurnsForExecutor(1L, 9L, java.util.Set.of(55L));
    }

    @Test
    void firstHeartbeatAfterReplacementImmediatelyRecoversInactiveTurns() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        es.markReplacementRecoveryPending();
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(PresenceManager.SessionMutationResult.APPLIED);

        router.route(es, heartbeat("[]"));

        verify(convSvc).recoverInactiveTurnsForReplacedExecutor(1L, 9L, java.util.Set.of());
        verify(convSvc, never()).recoverStaleTurnsForExecutor(anyLong(), anyLong(), any());
    }

    @Test
    void legacyHeartbeatRemainsCompatibleWithoutInventingConversationActivity() throws Exception {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        when(presenceManager.publishHeartbeat(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(PresenceManager.SessionMutationResult.APPLIED);

        router.route(es, "{\"type\":\"HEARTBEAT\"}");

        verify(convSvc, never()).recoverStaleTurnsForExecutor(anyLong(), anyLong(), any());
        verify(presenceManager).publishHeartbeat(eq(9L), eq(3L), isNull(), any(),
                isNull(), isNull(), isNull());
        verify(es.getSession(), never()).close();
    }

    @Test
    void routesRuntimeMcpConnectionTestResult() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));

        router.route(es, "{\"type\":\"MCP_CONNECTION_TEST_RESULT\",\"testId\":\"test-1\","
                + "\"success\":true,\"message\":\"连接成功\",\"durationMs\":12}");

        verify(runtimeMcpConnectionTestService).complete(1L, 9L, "test-1", true, "连接成功", 12L, List.of());
    }
}
