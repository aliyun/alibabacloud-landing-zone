package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.conversation.ConversationCommandsService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.skill.RuntimeMcpConnectionTestService;
import javax.websocket.Session;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class InboundFrameRouterCommandsTest {

    private final DispatchService dispatchService = mock(DispatchService.class);
    private final ArtifactService artifactService = mock(ArtifactService.class);
    private final PresenceManager presenceManager = mock(PresenceManager.class);
    private final HandoffService handoffService = mock(HandoffService.class);
    private final DispatchDrainScheduler drainScheduler = mock(DispatchDrainScheduler.class);
    private final DispatchPauseService pauseService = mock(DispatchPauseService.class);
    private final GuidanceService guidanceService = mock(GuidanceService.class);
    private final InteractionWorkflowService interactionWorkflowService = mock(InteractionWorkflowService.class);
    private final RuntimeMcpConnectionTestService runtimeMcpConnectionTestService =
            mock(RuntimeMcpConnectionTestService.class);
    private final ConversationCommandsService commandsService = mock(ConversationCommandsService.class);

    private final InboundFrameRouter router = new InboundFrameRouter(dispatchService, artifactService,
            presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
            interactionWorkflowService, null, null, runtimeMcpConnectionTestService);

    @Test
    void routesCommandsResultToService() {
        router.setConversationCommandsService(commandsService);
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));

        String msg = "{\"type\":\"CONVERSATION_COMMANDS_RESULT\",\"conversationId\":42,"
                + "\"status\":\"OK\",\"commands\":\"{\\\"availableCommands\\\":[]}\",\"error\":\"\"}";
        router.route(es, msg);

        // executorId 取自回连会话（es.getExecutorId()），不是帧内容：服务端据此校验归属
        verify(commandsService).onResult(1L, 9L, 42L, "OK", "{\"availableCommands\":[]}", "");
    }
}
