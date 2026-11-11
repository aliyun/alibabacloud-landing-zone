package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationLaunchVO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkitemClarificationLaunchServiceTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long WORKITEM_ID = 501L;
    private static final long AGENT_ID = 5L;
    private static final long USER_ID = 7L;

    private final WorkitemDao workitemDao = mock(WorkitemDao.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkitemClarificationConversationService conversationService =
            mock(WorkitemClarificationConversationService.class);

    private final WorkitemClarificationLaunchService service =
            new WorkitemClarificationLaunchService(workitemDao, agentService, conversationService);

    private WorkitemDO workitem(Long tenantId) {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(WORKITEM_ID);
        workitem.setTenantId(tenantId);
        return workitem;
    }

    private AgentVO agent(Long onlineVersionId) {
        AgentVO agent = new AgentVO();
        agent.setId(AGENT_ID);
        agent.setName("Clarifier");
        agent.setOnlineVersionId(onlineVersionId);
        return agent;
    }

    private ClarificationConversationVO conversation() {
        return ClarificationConversationVO.builder()
                .id(77L)
                .agentId(AGENT_ID)
                .agentName("Clarifier")
                .status("ACTIVE")
                .executorOnline(true)
                .build();
    }

    @Test
    void startRejectsUnknownWorkitemAndWorkitemFromAnotherWorkspace() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(null);
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID, "hi", null, null)).getCode());

        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(999L));
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID, "hi", null, null)).getCode());

        verifyNoInteractions(agentService, conversationService);
    }

    @Test
    void startRejectsMissingAndNotOnlineAgent() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKSPACE_ID));
        doThrow(new BizException(ErrorCode.AGENT_NOT_FOUND)).when(agentService).get(AGENT_ID, WORKSPACE_ID);
        assertEquals(ErrorCode.AGENT_NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID, "hi", null, null)).getCode());

        doReturn(agent(null)).when(agentService).get(AGENT_ID, WORKSPACE_ID);
        assertEquals(ErrorCode.AGENT_NOT_ONLINE.getCode(), assertThrows(BizException.class,
                () -> service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID, "hi", null, null)).getCode());

        verifyNoInteractions(conversationService);
    }

    @Test
    void startReusesConversationAndSubmitsFirstMessageWithIdempotencyKey() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKSPACE_ID));
        when(agentService.get(AGENT_ID, WORKSPACE_ID)).thenReturn(agent(11L));
        when(conversationService.getOrCreateConversation(WORKSPACE_ID, WORKITEM_ID, AGENT_ID))
                .thenReturn(conversation());

        ClarificationLaunchVO launched = service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID,
                "read the repo first", "nightly-1", null);

        assertEquals(77L, launched.getConversationId());
        assertEquals(AGENT_ID, launched.getAgentId());
        assertEquals("Clarifier", launched.getAgentName());
        assertEquals("ACTIVE", launched.getStatus());
        assertTrue(launched.isExecutorOnline());
        assertTrue(launched.isTurnSubmitted());
        verify(conversationService).submitTurn(WORKSPACE_ID, WORKITEM_ID, 77L, USER_ID,
                "read the repo first", "nightly-1", null);
    }

    @Test
    void startSubmitsAttachmentsEvenWithoutText() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKSPACE_ID));
        when(agentService.get(AGENT_ID, WORKSPACE_ID)).thenReturn(agent(11L));
        when(conversationService.getOrCreateConversation(WORKSPACE_ID, WORKITEM_ID, AGENT_ID))
                .thenReturn(conversation());

        ClarificationLaunchVO launched = service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID,
                "  ", "attachments-1", List.of(31L));

        assertTrue(launched.isTurnSubmitted());
        verify(conversationService).submitTurn(WORKSPACE_ID, WORKITEM_ID, 77L, USER_ID,
                "  ", "attachments-1", List.of(31L));
    }

    @Test
    void startWithoutMessageOnlyEnsuresTheConversation() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKSPACE_ID));
        when(agentService.get(AGENT_ID, WORKSPACE_ID)).thenReturn(agent(11L));
        when(conversationService.getOrCreateConversation(WORKSPACE_ID, WORKITEM_ID, AGENT_ID))
                .thenReturn(conversation());

        ClarificationLaunchVO launched = service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID,
                "   ", null, List.of());

        assertFalse(launched.isTurnSubmitted());

        ClarificationLaunchVO withoutContent = service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID,
                null, null, null);

        assertFalse(withoutContent.isTurnSubmitted());
        verify(conversationService, times(2))
                .getOrCreateConversation(WORKSPACE_ID, WORKITEM_ID, AGENT_ID);
        verify(conversationService, never()).submitTurn(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void startSurfacesOfflineExecutorFromTheTurnSubmission() {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKSPACE_ID));
        when(agentService.get(AGENT_ID, WORKSPACE_ID)).thenReturn(agent(11L));
        when(conversationService.getOrCreateConversation(WORKSPACE_ID, WORKITEM_ID, AGENT_ID))
                .thenReturn(conversation());
        doThrow(new BizException(ErrorCode.CLARIFICATION_EXECUTOR_OFFLINE)).when(conversationService)
                .submitTurn(WORKSPACE_ID, WORKITEM_ID, 77L, USER_ID, "hi", "m-1", null);

        assertEquals(ErrorCode.CLARIFICATION_EXECUTOR_OFFLINE.getCode(), assertThrows(BizException.class,
                () -> service.start(WORKSPACE_ID, WORKITEM_ID, AGENT_ID, USER_ID, "hi", "m-1", null)).getCode());
    }
}
