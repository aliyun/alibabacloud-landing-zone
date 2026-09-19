package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.dto.ElicitationReplyRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemClarificationConversationControllerTest {

    private WorkitemClarificationConversationService service;
    private ConversationTurnEventService turnEventService;
    private ConversationCommandsService commandsService;
    private WorkitemClarificationConversationController controller;

    @BeforeEach
    void setUp() {
        service = mock(WorkitemClarificationConversationService.class);
        turnEventService = mock(ConversationTurnEventService.class);
        commandsService = mock(ConversationCommandsService.class);
        controller = new WorkitemClarificationConversationController(service, turnEventService,
                commandsService);
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
    }

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    /** S18：回答会改变会话状态并驱动 Agent 继续，必须要求 READ_WRITE。 */
    @Test
    void replyElicitationRequiresReadWriteAccess() throws NoSuchMethodException {
        RequireWorkspaceAccess access = WorkitemClarificationConversationController.class
                .getMethod("replyElicitation", Long.class, Long.class, String.class,
                        ElicitationReplyRequest.class)
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
    }

    @Test
    void replyElicitationDelegatesActionAndContent() {
        ElicitationReplyRequest request = new ElicitationReplyRequest();
        request.setAction("accept");
        request.setContent("{\"q0\":\"A\"}");

        controller.replyElicitation(10011L, 77L, "req-1", request);

        verify(service).replyElicitation(1L, 10011L, 77L, "req-1", "accept", "{\"q0\":\"A\"}");
    }

    /** S15：按轮次取事件端点返回该轮全部事件，并先校验会话归属。 */
    @Test
    void turnEventsVerifiesOwnershipThenReturnsAllEventsOfThatTurn() {
        AgentConversationTurnEventDO event = new AgentConversationTurnEventDO();
        event.setId(5L);
        when(turnEventService.listEventsByTurn(1L, 77L, 55L)).thenReturn(List.of(event));

        List<AgentConversationTurnEventDO> events =
                controller.turnEvents(10011L, 77L, 55L).getData();

        verify(service).verifyConversationBelongsToWorkitem(1L, 10011L, 77L);
        assertEquals(1, events.size());
        assertEquals(5L, events.get(0).getId());
    }

    /** S16：不属于该工单的会话必须在取事件之前就被拒掉，别泄露别人的时间线。 */
    @Test
    void turnEventsRejectsConversationFromAnotherWorkitem() {
        doThrow(new IllegalArgumentException("conversation does not belong to this workitem"))
                .when(service).verifyConversationBelongsToWorkitem(1L, 99999L, 77L);

        assertThrows(IllegalArgumentException.class,
                () -> controller.turnEvents(99999L, 77L, 55L));
        verify(turnEventService, never()).listEventsByTurn(anyLong(), anyLong(), anyLong());
    }
}
