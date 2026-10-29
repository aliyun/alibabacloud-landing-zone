package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
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
    private final ClarificationAttachmentService attachmentService =
            mock(ClarificationAttachmentService.class);

    private final WorkitemClarificationConversationService service =
            new WorkitemClarificationConversationService(convDao, turnDao, conversationService,
                    agentDao, runtimePresence, elicitationService, commandsService,
                    executorRouter, attachmentService);

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
    void expiredHistorySurvivesTerminalAndHistoryReadFailureIsExplicit() {
        when(convDao.findById(1L,77L)).thenReturn(clarificationConversation(10011L));
        var expired=new AgentConversationElicitationDO();expired.setRequestId("expired");expired.setTurnId(55L);
        expired.setStatus("EXPIRED");expired.setMessage("Original question");expired.setSchemaJson("{}");
        when(elicitationService.listQuestionHistory(1L,77L)).thenReturn(java.util.List.of(expired));
        var vo=service.getConversation(1L,10011L,77L);
        assertEquals("EXPIRED",vo.getQuestionHistory().getFirst().getStatus());
        assertEquals("Original question",vo.getQuestionHistory().getFirst().getMessage());
        assertTrue(vo.getPendingElicitations().isEmpty());assertTrue(vo.getAnswerDeliveries().isEmpty());
        verifyNoInteractions(conversationService);
        when(elicitationService.listQuestionHistory(1L,77L)).thenThrow(new IllegalStateException("read failed"));
        assertThrows(IllegalStateException.class,()->service.getConversation(1L,10011L,77L));
    }

    @Test
    void replayFromAnotherActorCannotBorrowAttachments() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        var persisted = turn(90L, "IN", "look");
        persisted.setSubmittedUserId(41L);
        when(turnDao.findByExternalMsgId(1L, "web-clarification:77:c-1")).thenReturn(persisted);
        assertThrows(BizException.class, () -> service.submitTurn(1L, 10011L, 77L, 42L,
                "look", "c-1", java.util.List.of(5L)));
        verifyNoInteractions(attachmentService, conversationService);
    }

    @Test
    void legacyReplayWithoutActorCannotBindAttachments() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.findByExternalMsgId(1L, "web-clarification:c-1"))
                .thenReturn(turn(90L, "IN", "look"));
        assertThrows(BizException.class, () -> service.submitTurn(1L, 10011L, 77L, 42L,
                "look", "c-1", java.util.List.of(5L)));
        verifyNoInteractions(attachmentService, conversationService);
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

        verify(elicitationService).reply(1L, 77L, "req-1", "accept", "{\"q0\":\"A\"}", null);
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
        when(turnDao.findByExternalMsgId(1L, "web-clarification:77:client-1"))
                .thenReturn(null, turn(90L, "IN", "clarify"));

        service.submitTurn(1L, 10011L, 77L, 42L, "clarify", "client-1", null);

        verify(convDao).updateExecutor(1L, 77L, 10L);
        verify(conversationService).submitTurn(1L, 3L, "WORKITEM_CLARIFICATION",
                conv.getChannelConversationId(), "clarify", "web-clarification:77:client-1", null, 42L);
    }

    /** E2E 回归：执行器离线返回可判定的业务错误码，而不是落到 10000 系统错误。 */
    @Test
    void submitTurnReportsCodedErrorWhenNoExecutorAvailable() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        when(executorRouter.select(1L, 3L, 50L, 9L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.submitTurn(1L, 10011L, 77L, 42L, "hello", "c-offline", null));

        assertEquals(ErrorCode.CLARIFICATION_EXECUTOR_OFFLINE.getCode(), ex.getCode());
        verify(conversationService, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void submitTurnBindsUploadedAttachmentsToPersistedTurn() {
        AgentConversationDO conv = clarificationConversation(10011L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        when(executorRouter.select(1L, 3L, 50L, 9L)).thenReturn(9L);
        when(turnDao.findByExternalMsgId(1L, "web-clarification:77:c-1"))
                .thenReturn(null, turn(90L, "IN", "look at this"));
        when(attachmentService.requireBindableIds(1L, 77L, 42L, java.util.List.of(5L, 6L)))
                .thenReturn(java.util.List.of(5L, 6L));

        service.submitTurn(1L, 10011L, 77L, 42L, "look at this", "c-1",
                java.util.List.of(5L, 6L));

        verify(attachmentService).bindToTurn(1L, 77L, 42L,
                java.util.List.of(5L, 6L), 90L);
    }

    @Test
    void submitTurnAllowsAttachmentOnlyMessage() {
        AgentConversationDO conv = clarificationConversation(10011L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        when(executorRouter.select(1L, 3L, 50L, 9L)).thenReturn(9L);
        when(turnDao.findByExternalMsgId(1L, "web-clarification:77:c-2"))
                .thenReturn(null, turn(91L, "IN", ""));
        when(attachmentService.requireBindableIds(1L, 77L, 42L, java.util.List.of(5L)))
                .thenReturn(java.util.List.of(5L));

        service.submitTurn(1L, 10011L, 77L, 42L, "   ", "c-2",
                java.util.List.of(5L));

        verify(conversationService).submitTurn(1L, 3L, "WORKITEM_CLARIFICATION",
                conv.getChannelConversationId(), "", "web-clarification:77:c-2", null, 42L);
        verify(attachmentService).bindToTurn(1L, 77L, 42L, java.util.List.of(5L), 91L);
    }

    @Test
    void submitTurnRejectsEmptyMessageWithoutAttachments() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.submitTurn(1L, 10011L, 77L, 42L, "   ", "c-3",
                        java.util.List.of()));
        verify(conversationService, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void submitTurnReplayDoesNotDispatchAgainAndBindsUnboundAttachments() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        AgentConversationTurnDO persisted = turn(90L, "IN", "look");
        persisted.setSubmittedUserId(42L);
        when(turnDao.findByExternalMsgId(1L, "web-clarification:77:c-1")).thenReturn(persisted);
        when(attachmentService.verifyReplay(1L, 77L, 42L, java.util.List.of(5L), 90L))
                .thenReturn(java.util.List.of(5L));

        service.submitTurn(1L, 10011L, 77L, 42L, "look", "c-1",
                java.util.List.of(5L));

        verify(attachmentService).verifyReplay(1L, 77L, 42L, java.util.List.of(5L), 90L);
        verify(attachmentService).bindToTurn(1L, 77L, 42L, java.util.List.of(5L), 90L);
        verify(conversationService, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void getConversationAttachesHistoryAttachmentsPerTurn() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of(
                turn(1L, "IN", "带图的问题"), turn(2L, "OUT", "回答")));
        com.aliyun.autowonder.conversation.dto.ClarificationAttachmentVO image =
                com.aliyun.autowonder.conversation.dto.ClarificationAttachmentVO.builder()
                        .id(5L).name("shot.png").contentType("image/png").size(12L)
                        .previewable(true).build();
        when(attachmentService.listByTurnIds(1L, 77L, java.util.List.of(1L, 2L)))
                .thenReturn(java.util.Map.of(1L, java.util.List.of(image)));

        ClarificationConversationVO vo = service.getConversation(1L, 10011L, 77L);

        assertEquals(1, vo.getTurns().get(0).getAttachments().size());
        assertEquals("shot.png", vo.getTurns().get(0).getAttachments().get(0).getName());
        assertTrue(vo.getTurns().get(1).getAttachments().isEmpty());
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
                        agentDao, null, elicitationService, commandsService, executorRouter,
                        attachmentService);
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
    @Test
    void projectsOnlyFiniteBrowserDisplayWithoutSourcePrivacy() {
        when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(10011L));
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(55L); turn.setDirection("OUT"); turn.setStatus("CANCELED");
        turn.setContent("响应已终止");
        turn.setSourceContext("{\"senderNick\":\"private\",\"conversationDisplay\":{\"content\":{\"code\":\"CANCELED\",\"args\":{}}}}");
        when(turnDao.listTurnsByConversation(1L, 77L)).thenReturn(java.util.List.of(turn));
        var json = com.aliyun.autowonder.json.JSON.parseObject(com.aliyun.autowonder.json.JSON.toJSONString(
                service.getConversation(1L, 10011L, 77L).getTurns().get(0)));
        assertEquals("响应已终止", json.getString("content"));
        assertFalse(json.containsKey("sourceContext"));
        assertFalse(json.toJSONString().contains("private"));
        assertNotNull(json.getJSONObject("display"));
        assertEquals("CANCELED", json.getJSONObject("display").getJSONObject("content").getString("code"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "wrong", "agent"})
    void humanValidationKeepsRawTypeAndLocalizesAtGlobal200(String branch) {
        if ("wrong".equals(branch)) when(convDao.findById(1L, 77L)).thenReturn(clarificationConversation(999L));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            if ("agent".equals(branch)) service.createConversation(1L, 10011L, 3L);
            else service.verifyConversationBelongsToWorkitem(1L, 10011L, 77L);
        });
        String raw = switch(branch) { case "wrong" -> "conversation does not belong to this workitem";
            case "agent" -> "agent not found"; default -> "conversation not found"; };
        assertEquals(raw, error.getMessage()); assertNull(error.getCause());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        try {
            var result = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleIllegalArg(error);
            assertEquals("10001", result.getCode());
            assertEquals(switch(branch) {case "wrong" -> "This conversation does not belong to this Workitem.";
                case "agent" -> "Agent not found."; default -> "Conversation not found.";}, result.getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            assertEquals(switch(branch) {case "wrong" -> "此会话不属于当前工单。";
                case "agent" -> "数字员工不存在。"; default -> "会话不存在。";},
                new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleIllegalArg(error).getMessage());

        } finally {org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();}
    }

}
