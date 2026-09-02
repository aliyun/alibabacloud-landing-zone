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
import static org.mockito.Mockito.*;

class InboundFrameRouterConversationTest {

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
    void routesConversationTurnAckToService() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        String frame = "{\"type\":\"CONVERSATION_TURN_ACK\",\"conversationId\":77,\"turnId\":55,"
                + "\"status\":\"SUCCESS\",\"replyMarkdown\":\"hi\",\"sessionId\":\"s1\"}";

        router.route(es, frame);

        verify(convSvc).acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "hi", "s1");
    }

    @Test
    void heartbeatRecoversStaleConversationTurnsForExecutor() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        when(presenceManager.heartbeat(eq(9L), eq(3L), eq(1), any())).thenReturn(true);
        String frame = "{\"type\":\"HEARTBEAT\",\"runningConversationTurnIds\":[55]}";

        router.route(es, frame);

        verify(convSvc).recoverStaleTurnsForExecutor(1L, 9L, java.util.Set.of(55L));
    }

    @Test
    void firstHeartbeatAfterReplacementImmediatelyRecoversInactiveTurns() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        es.markReplacementRecoveryPending();
        when(presenceManager.heartbeat(eq(9L), eq(3L), eq(1), any())).thenReturn(true);

        router.route(es, "{\"type\":\"HEARTBEAT\",\"runningConversationTurnIds\":[]}");

        verify(convSvc).recoverInactiveTurnsForReplacedExecutor(1L, 9L, java.util.Set.of());
        verify(convSvc, never()).recoverStaleTurnsForExecutor(anyLong(), anyLong(), any());
    }

    @Test
    void legacyHeartbeatDoesNotRecoverConversationTurnsWithoutRuntimeActivityReport() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));
        when(presenceManager.heartbeat(9L, 3L, 1)).thenReturn(true);

        router.route(es, "{\"type\":\"HEARTBEAT\"}");

        verify(convSvc, never()).recoverStaleTurnsForExecutor(anyLong(), anyLong(), any());
    }

    @Test
    void routesRuntimeMcpConnectionTestResult() {
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));

        router.route(es, "{\"type\":\"MCP_CONNECTION_TEST_RESULT\",\"testId\":\"test-1\","
                + "\"success\":true,\"message\":\"连接成功\",\"durationMs\":12}");

        verify(runtimeMcpConnectionTestService).complete(1L, 9L, "test-1", true, "连接成功", 12L, List.of());
    }
}
