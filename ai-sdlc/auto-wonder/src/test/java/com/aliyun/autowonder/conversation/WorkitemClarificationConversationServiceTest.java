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

    /** S14：前端据此决定是否启用卡片交互入口，必须随 feature 变化。 */
    @Test
    void getConversationReportsAcpInteractionSupportedWhenRuntimeNegotiatesFeature() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(9L, "CONVERSATION_ACP_INTERACTION_V1"))
                .thenReturn(true);

        assertTrue(service.getConversation(1L, 10011L, 77L).isAcpInteractionSupported());
    }

    @Test
    void getConversationHidesAcpInteractionWhenRuntimeLacksFeature() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(9L, "CONVERSATION_ACP_INTERACTION_V1"))
                .thenReturn(false);

        assertFalse(service.getConversation(1L, 10011L, 77L).isAcpInteractionSupported());
    }

    /** 执行器离线时卡片提交必然失败，不该把交互入口亮出来。 */
    @Test
    void getConversationHidesAcpInteractionWhenExecutorOffline() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(false);
        when(runtimePresence.supportsProtocolFeature(9L, "CONVERSATION_ACP_INTERACTION_V1"))
                .thenReturn(true);

        assertFalse(service.getConversation(1L, 10011L, 77L).isAcpInteractionSupported());
    }

    /** 浏览器刷新后要能从服务端状态恢复未解决卡片，而不是把用户卡在空白处。 */
    @Test
    void getConversationEchoesPendingElicitationsForRefreshRecovery() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());
        AgentConversationElicitationDO pending = new AgentConversationElicitationDO();
        pending.setRequestId("req-1");
        pending.setTurnId(55L);
        pending.setMode("form");
        pending.setMessage("pick one");
        pending.setSchemaJson("{\"type\":\"object\"}");
        pending.setStatus("PENDING");
        when(elicitationService.listPending(1L, 77L)).thenReturn(java.util.List.of(pending));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(1, vo.getPendingElicitations().size());
        assertEquals("req-1", vo.getPendingElicitations().get(0).getRequestId());
        assertEquals(55L, vo.getPendingElicitations().get(0).getTurnId());
        assertEquals("pick one", vo.getPendingElicitations().get(0).getMessage());
        assertEquals("{\"type\":\"object\"}",
                vo.getPendingElicitations().get(0).getRequestedSchema());
    }

    /** 列表接口不该为每个会话都去查挂起卡片，那是详情页才需要的数据。 */
    @Test
    void listConversationsDoesNotLoadPendingElicitations() {
        when(convDao.listByBizRef(1L, "WORKITEM_CLARIFICATION", "WORKITEM", 10011L, 3L))
                .thenReturn(java.util.List.of(clarificationConversation(10011L)));

        service.listConversations(1L, 10011L, 3L);

        verify(elicitationService, never()).listPending(anyLong(), anyLong());
    }

    @Test
    void replyElicitationDelegatesToElicitationService() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));

        service.replyElicitation(1L, 10011L, 77L, "req-1", "accept", "{\"q0\":\"A\"}");

        verify(elicitationService).reply(1L, 77L, "req-1", "accept", "{\"q0\":\"A\"}");
    }

    /** S16 的回答侧对偶：跨工单猜 conversationId 必须在触达卡片之前就被拒。 */
    @Test
    void replyElicitationRejectsConversationFromOtherWorkitem() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));

        assertThrows(IllegalArgumentException.class,
                () -> service.replyElicitation(1L, 99999L, 77L, "req-1", "accept", "{}"));
        verify(elicitationService, never()).reply(anyLong(), anyLong(), anyString(), anyString(),
                anyString());
    }

    @Test
    void submitTurnCanFallbackFromPreferredExecutorThroughFeatureAwareRouter() {
        AgentConversationDO conv = clarificationConversation(10011L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        when(executorRouter.select(1L, 3L, 50L, 9L)).thenReturn(10L);

        service.submitTurn(1L, 10011L, 77L, "clarify", "client-1");

        verify(convDao).updateExecutor(1L, 77L, 10L);
        verify(conversationService).submitTurn(1L, 3L, "WORKITEM_CLARIFICATION",
                conv.getChannelConversationId(), "clarify", "web-clarification:client-1");
    }

    private AgentConversationTurnDO turn(long id, String direction, String content) {
        AgentConversationTurnDO t = new AgentConversationTurnDO();
        t.setId(id);
        t.setTenantId(1L);
        t.setConversationId(77L);
        t.setDirection(direction);
        t.setContent(content);
        t.setStatus("SUCCESS");
        return t;
    }

    private void mockConversationWithHistory() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of(
                turn(1L, "IN", "第一个问题"),
                turn(2L, "OUT", "第一段回答")));
    }

    /** 修复要求 3：命令快照（Redis 种子）失败时，已有历史仍完整返回且内容与顺序不变。 */
    @Test
    void getConversationReturnsHistoryWhenCommandsSnapshotFails() {
        mockConversationWithHistory();
        when(commandsService.snapshot(1L, 77L)).thenThrow(new RuntimeException("redis down"));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(2, vo.getTurns().size());
        assertEquals("第一个问题", vo.getTurns().get(0).getContent());
        assertEquals("第一段回答", vo.getTurns().get(1).getContent());
        assertTrue(vo.getAvailableCommands().isEmpty());
    }

    /** 修复要求 3：挂起卡片查询失败降级为空，不阻断已有历史返回。 */
    @Test
    void getConversationReturnsHistoryWhenPendingElicitationsFail() {
        mockConversationWithHistory();
        when(elicitationService.listPending(1L, 77L)).thenThrow(new RuntimeException("db glitch"));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(2, vo.getTurns().size());
        assertTrue(vo.getPendingElicitations().isEmpty());
    }

    /** 修复要求 3：在线状态查询失败按离线降级，不阻断已有历史返回。 */
    @Test
    void getConversationReturnsHistoryWhenExecutorPresenceFails() {
        mockConversationWithHistory();
        when(runtimePresence.isExecutorOnline(9L)).thenThrow(new RuntimeException("presence down"));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(2, vo.getTurns().size());
        assertFalse(vo.isExecutorOnline());
        assertFalse(vo.isCancelSupported());
        assertFalse(vo.isAcpInteractionSupported());
    }

    /** 在线判定成功但协议能力协商失败：按不支持降级，历史照常返回。 */
    @Test
    void getConversationReturnsHistoryWhenProtocolFeatureNegotiationFails() {
        mockConversationWithHistory();
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(eq(9L), anyString()))
                .thenThrow(new RuntimeException("negotiation down"));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(2, vo.getTurns().size());
        assertTrue(vo.isExecutorOnline());
        assertFalse(vo.isStreamingSupported());
        assertFalse(vo.isCancelSupported());
        assertFalse(vo.isAcpInteractionSupported());
    }

    /** 未绑定执行器的会话走降级兜底分支：能力位全部为 false，历史不受影响。 */
    @Test
    void getConversationTreatsUnboundExecutorAsOffline() {
        mockConversationWithHistory();
        AgentConversationDO unbound = clarificationConversation(10011L);
        unbound.setExecutorId(null);
        when(convDao.findById(1L, 77L)).thenReturn(unbound);

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertFalse(vo.isExecutorOnline());
        assertEquals(2, vo.getTurns().size());
    }

    /** runtimePresence 缺失（未装配）同样降级为离线，不抛 NPE。 */
    @Test
    void getConversationDegradesWhenRuntimePresenceMissing() {
        WorkitemClarificationConversationService bare =
                new WorkitemClarificationConversationService(convDao, turnDao, conversationService,
                        agentDao, null, elicitationService, commandsService, executorRouter);
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of());

        ClarificationConversationVO vo = bare.getConversation(1L, 10011L, 77L);

        assertFalse(vo.isExecutorOnline());
    }

    /** 历史正文读取失败必须显式报错：绝不伪造成功空数组（工单 55411 修复要求 3）。 */
    @Test
    void getConversationFailsExplicitlyWhenHistoryReadFails() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L))
                .thenThrow(new RuntimeException("history db down"));

        assertThrows(RuntimeException.class, () -> service.getConversation(1L, 10011L, 77L));
    }

    /** 列表页的在线位同样降级：presence 故障不能让会话列表整体失败。 */
    @Test
    void listConversationsDegradesWhenExecutorPresenceFails() {
        when(convDao.listByBizRef(1L, "WORKITEM_CLARIFICATION", "WORKITEM", 10011L, 3L))
                .thenReturn(java.util.List.of(clarificationConversation(10011L)));
        when(runtimePresence.isExecutorOnline(9L)).thenThrow(new RuntimeException("presence down"));

        java.util.List<ClarificationConversationVO> list = service.listConversations(1L, 10011L, 3L);

        assertEquals(1, list.size());
        assertFalse(list.get(0).isExecutorOnline());
    }
}
