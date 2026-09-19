package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationSlashCommandVO;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemClarificationConversationServiceCommandsTest {

    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final AgentConversationService conversationService = mock(AgentConversationService.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final ExecutorSelector executorSelector = mock(ExecutorSelector.class);
    private final ConversationRuntimePresence runtimePresence = mock(ConversationRuntimePresence.class);
    private final ConversationElicitationService elicitationService =
            mock(ConversationElicitationService.class);
    private final ConversationCommandsService commandsService =
            mock(ConversationCommandsService.class);
    private final ConversationExecutorRouter executorRouter = mock(ConversationExecutorRouter.class);

    private final WorkitemClarificationConversationService service =
            new WorkitemClarificationConversationService(convDao, turnDao, conversationService,
                    agentDao, runtimePresence, elicitationService, commandsService,
                    executorRouter);

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

    private ClarificationSlashCommandVO command(String name) {
        ClarificationSlashCommandVO vo = new ClarificationSlashCommandVO();
        vo.setName(name);
        return vo;
    }

    /** 详情接口带出上次探针的命令快照，前端打开会话即秒显 `/` 候选。 */
    @Test
    void getConversationSeedsAvailableCommandsFromSnapshot() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(List.of());
        when(commandsService.snapshot(1L, 77L)).thenReturn(List.of(command("quest")));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(1, vo.getAvailableCommands().size());
        assertEquals("quest", vo.getAvailableCommands().get(0).getName());
        verify(commandsService).snapshot(1L, 77L);
    }

    /** 列表页逐会话读 Redis 是纯浪费：命令种子只属于详情路径。 */
    @Test
    void listConversationsDoesNotReadCommandSnapshot() {
        when(convDao.listByBizRef(1L, "WORKITEM_CLARIFICATION", "WORKITEM", 10011L, 3L))
                .thenReturn(List.of(clarificationConversation(10011L)));

        List<ClarificationConversationVO> vos = service.listConversations(1L, 10011L, 3L);

        verify(commandsService, never()).snapshot(anyLong(), anyLong());
        assertTrue(vos.get(0).getAvailableCommands().isEmpty());
    }

    /** 新建会话没有探针历史，种子为空列表且不触发 Redis 读。 */
    @Test
    void createConversationDoesNotReadCommandSnapshot() {
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        agent.setName("澄清数字人");
        when(agentDao.findById(3L)).thenReturn(agent);
        when(executorRouter.select(1L, 3L, 50L, null)).thenReturn(9L);

        ClarificationConversationVO vo = service.createConversation(1L, 10011L, 3L);

        verify(commandsService, never()).snapshot(anyLong(), anyLong());
        assertTrue(vo.getAvailableCommands().isEmpty());
    }
}
