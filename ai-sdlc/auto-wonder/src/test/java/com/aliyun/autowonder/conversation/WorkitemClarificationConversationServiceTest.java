package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkitemClarificationConversationServiceTest {

    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final AgentConversationService conversationService = mock(AgentConversationService.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final ExecutorSelector executorSelector = mock(ExecutorSelector.class);
    private final ConversationRuntimePresence runtimePresence = mock(ConversationRuntimePresence.class);

    private final WorkitemClarificationConversationService service =
            new WorkitemClarificationConversationService(convDao, turnDao, conversationService,
                    agentDao, executorSelector, runtimePresence);

    private AgentConversationDO clarificationConversation(Long workitemId) {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("WORKITEM_CLARIFICATION");
        conv.setBizRefType("WORKITEM");
        conv.setBizRefId(workitemId);
        conv.setAgentId(3L);
        conv.setExecutorId(9L);
        conv.setStatus("ACTIVE");
        return conv;
    }

    @Test
    void cancelTurnDelegatesToConversationService() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));

        service.cancelTurn(1L, 10011L, 77L, 55L);

        verify(conversationService).requestTurnCancel(1L, 77L, 55L);
    }

    @Test
    void cancelTurnRejectsConversationFromOtherWorkitem() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));

        assertThrows(IllegalArgumentException.class,
                () -> service.cancelTurn(1L, 99999L, 77L, 55L));
        verify(conversationService, never()).requestTurnCancel(anyLong(), anyLong(), anyLong());
    }

    @Test
    void getConversationReportsCancelSupportedWhenRuntimeNegotiatesFeature() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(9L, "CONVERSATION_TURN_CANCEL")).thenReturn(true);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setName("澄清数字人");
        when(agentDao.findById(3L)).thenReturn(agent);

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertTrue(vo.isExecutorOnline());
        assertTrue(vo.isCancelSupported());
    }

    @Test
    void getConversationHidesCancelSupportWhenRuntimeLacksFeature() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(9L, "CONVERSATION_TURN_CANCEL")).thenReturn(false);
        when(agentDao.findById(3L)).thenReturn(null);

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertFalse(vo.isCancelSupported());
    }

    @Test
    void getConversationSurfacesQueuedTurnAsProcessing() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        AgentConversationTurnDO queued = new AgentConversationTurnDO();
        queued.setId(55L);
        queued.setTenantId(1L);
        queued.setConversationId(77L);
        queued.setDirection("IN");
        queued.setStatus("QUEUED");
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(queued);

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals("QUEUED", vo.getProcessingStatus());
        assertEquals(55L, vo.getProcessingTurnId());
    }
}
