package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentConversationServiceTest {

    private final AgentConversationDao convDao = mock(AgentConversationDao.class);
    private final AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
    private final ConversationTransport transport = mock(ConversationTransport.class);
    private final ExecutorSelector executorSelector = mock(ExecutorSelector.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final AgentVersionDao agentVersionDao = mock(AgentVersionDao.class);
    private final ConversationChannelSinkRegistry sinkRegistry = mock(ConversationChannelSinkRegistry.class);

    private final AgentConversationService svc = new AgentConversationService(
            convDao, turnDao, transport, executorSelector, agentDao, agentVersionDao, sinkRegistry);

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(turnDao.acquireConversationLock(anyString(), anyInt())).thenReturn(1);
        when(turnDao.releaseConversationLock(anyString())).thenReturn(1);
        when(turnDao.recordDispatchAttemptIfProcessing(anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(turnDao.claimStaleDispatchAttemptIfProcessing(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(1);
        when(convDao.updateAgentVersion(anyLong(), anyLong(), anyLong())).thenReturn(1);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO();
        version.setRoleName("默认数字人");
        when(agentVersionDao.findById(50L)).thenReturn(version);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MDC.clear();
        AutoWonderContext.destroy();
    }

    @Test
    void submitTurnIsIdempotentOnExternalMsgId() {
        when(turnDao.findByExternalMsgId(1L, "msg-1")).thenReturn(new AgentConversationTurnDO());
        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "hi", "msg-1");
        verify(transport, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void submitTurnFirstTurnSendsSystemPrompt() {
        when(turnDao.findByExternalMsgId(1L, "msg-2")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(9L);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        AgentVersionDO ver = new AgentVersionDO();
        ver.setRoleName("测试数字人");
        when(agentVersionDao.findById(50L)).thenReturn(ver);
        when(convDao.insert(any())).thenAnswer(inv -> {
            AgentConversationDO c = inv.getArgument(0);
            c.setId(77L);
            return 1;
        });
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(88L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "hi", "msg-2");
        commitTransactionSynchronizations();

        ArgumentCaptor<String> sysCap = ArgumentCaptor.forClass(String.class);
        verify(turnDao).acquireConversationLock(argThat(this::isLogicalConversationLock), eq(10));
        verify(turnDao).releaseConversationLock(argThat(this::isLogicalConversationLock));
        verify(convDao).insert(argThat(conversation ->
                Long.valueOf(50L).equals(conversation.getAgentVersionId())));
        verify(transport).send(any(), eq(88L), eq("hi"), sysCap.capture(), any());
        assertTrue(sysCap.getValue().contains("测试数字人"));
    }

    @Test
    void submitTurnUsesBoundedHashedLogicalConversationLockName() {
        String longChannelConversationId = "conv-" + "x".repeat(300);
        when(turnDao.findByExternalMsgId(1L, "msg-long")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", longChannelConversationId, 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(9L);
        when(convDao.insert(any())).thenAnswer(inv -> {
            AgentConversationDO c = inv.getArgument(0);
            c.setId(77L);
            return 1;
        });
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(88L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", longChannelConversationId, "hi", "msg-long");
        commitTransactionSynchronizations();

        ArgumentCaptor<String> lockName = ArgumentCaptor.forClass(String.class);
        verify(turnDao).acquireConversationLock(lockName.capture(), eq(10));
        assertTrue(lockName.getValue().startsWith("agent-conv-key:"));
        assertTrue(lockName.getValue().length() <= 64);
        assertFalse(lockName.getValue().contains(longChannelConversationId));
    }

    @Test
    void submitTurnStoresCurrentRequestIdOnInboundTurn() {
        AutoWonderContext.get().setRequestId("rid-submit");
        when(turnDao.findByExternalMsgId(1L, "msg-2")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(9L);
        when(convDao.insert(any())).thenAnswer(inv -> {
            AgentConversationDO c = inv.getArgument(0);
            c.setId(77L);
            return 1;
        });
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(88L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "hi", "msg-2");
        commitTransactionSynchronizations();

        verify(turnDao).insert(argThat(t -> "IN".equals(t.getDirection())
                && "rid-submit".equals(t.getRequestId())));
    }

    @Test
    void submitTurnStoresSourceContextOnInboundTurn() {
        when(turnDao.findByExternalMsgId(1L, "msg-2")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(9L);
        when(convDao.insert(any())).thenAnswer(inv -> {
            AgentConversationDO c = inv.getArgument(0);
            c.setId(77L);
            return 1;
        });
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(88L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "hi", "msg-2",
                "{\"senderNick\":\"王五\"}");
        commitTransactionSynchronizations();

        verify(turnDao).insert(argThat(t -> "IN".equals(t.getDirection())
                && "{\"senderNick\":\"王五\"}".equals(t.getSourceContext())));
    }

    @Test
    void submitTurnResumeForwardsStickyExecutorAndSendsCurrentSystemPrompt() {
        when(turnDao.findByExternalMsgId(1L, "msg-r")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);
        when(executorSelector.select(eq(3L), eq(9L))).thenReturn(9L);
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(90L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "more", "msg-r");
        commitTransactionSynchronizations();

        verify(executorSelector).select(eq(3L), eq(9L));
        verify(turnDao).insert(argThat(t -> "IN".equals(t.getDirection())
                && "more".equals(t.getContent())
                && "PROCESSING".equals(t.getStatus())));
        verify(turnDao).acquireConversationLock("agent-conversation:1:77", 10);
        verify(turnDao).releaseConversationLock("agent-conversation:1:77");
        verify(convDao, never()).updateExecutor(anyLong(), anyLong(), anyLong());
        verify(transport).send(any(), eq(90L), eq("more"), argThat(prompt ->
                prompt != null && prompt.contains("默认数字人")), any());
    }

    @Test
    void submitTurnRefreshesStoredAgentVersionBeforeResume() {
        when(turnDao.findByExternalMsgId(1L, "msg-version")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        conv.setAgentVersionId(49L);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);
        when(executorSelector.select(3L, 9L)).thenReturn(9L);
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO turn = inv.getArgument(0);
            turn.setId(91L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "refresh", "msg-version");
        commitTransactionSynchronizations();

        verify(convDao).updateAgentVersion(1L, 77L, 50L);
        assertEquals(50L, conv.getAgentVersionId());
        verify(transport).send(eq(conv), eq(91L), eq("refresh"), argThat(prompt ->
                prompt != null && prompt.contains("默认数字人")), any());
    }

    @Test
    void submitTurnRecordsDispatchAttemptBeforePostCommitSend() {
        when(turnDao.findByExternalMsgId(1L, "msg-r")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);
        when(executorSelector.select(eq(3L), eq(9L))).thenReturn(9L);
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(90L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "more", "msg-r");

        verify(turnDao).recordDispatchAttemptIfProcessing(1L, 77L, 90L);
        verify(transport, never()).send(any(), any(), any(), any(), any());

        commitTransactionSynchronizations();

        verify(transport).send(any(), eq(90L), eq("more"), anyString(), any());
    }

    @Test
    void submitTurnMarksTurnFailedWhenPostCommitDispatchFails() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        svc.configureFailureTransactionManager(transactionManager);
        when(turnDao.findByExternalMsgId(1L, "msg-r")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);
        when(executorSelector.select(eq(3L), eq(9L))).thenReturn(9L);
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(90L);
            return 1;
        });
        when(turnDao.updateInboundStatusIfProcessing(eq(1L), eq(77L), eq(90L),
                eq("FAILED"), anyString())).thenReturn(1);
        AgentConversationTurnDO inbound = processingInboundTurn(77L);
        inbound.setId(90L);
        inbound.setExternalMsgId("msg-r");
        when(turnDao.findByConversationTurn(1L, 77L, 90L)).thenReturn(inbound);
        AgentConversationTurnDO queued = new AgentConversationTurnDO();
        queued.setId(91L);
        queued.setTenantId(1L);
        queued.setConversationId(77L);
        queued.setDirection("IN");
        queued.setStatus("QUEUED");
        queued.setContent("next");
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(queued);
        when(turnDao.updateStatusIfCurrent(1L, 91L, "QUEUED", "PROCESSING", null))
                .thenReturn(1);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        doThrow(new IllegalArgumentException("duplicate capability MCP:autowonder"))
                .when(transport).send(any(), eq(90L), anyString(), anyString(), any());

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "more", "msg-r");
        assertDoesNotThrow(this::commitTransactionSynchronizations);

        verify(turnDao).updateInboundStatusIfProcessing(eq(1L), eq(77L), eq(90L),
                eq("FAILED"), argThat(error -> error.contains("duplicate capability MCP:autowonder")));
        verify(turnDao).insert(argThat(turn -> "OUT".equals(turn.getDirection())
                && "FAILED".equals(turn.getStatus())
                && turn.getContent().contains("duplicate capability MCP:autowonder")));
        verify(sink).deliverReply(eq(conv), contains("回复失败"), eq("msg-r"));
        verify(transport).send(eq(conv), eq(91L), eq("next"), anyString(), any());
    }

    @Test
    void submitTurnQueuesWhenConversationAlreadyProcessing() {
        when(turnDao.findByExternalMsgId(1L, "msg-q")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);
        AgentConversationTurnDO processing = new AgentConversationTurnDO();
        processing.setId(55L);
        processing.setTenantId(1L);
        processing.setConversationId(77L);
        processing.setDirection("IN");
        processing.setStatus("PROCESSING");
        when(turnDao.findProcessingInbound(1L, 77L)).thenReturn(processing);
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(90L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "queued", "msg-q");
        commitTransactionSynchronizations();

        verify(turnDao).insert(argThat(t -> "IN".equals(t.getDirection())
                && "queued".equals(t.getContent())
                && "msg-q".equals(t.getExternalMsgId())
                && "QUEUED".equals(t.getStatus())));
        verify(executorSelector, never()).select(anyLong(), nullable(Long.class));
        verify(convDao, never()).updateExecutor(anyLong(), anyLong(), anyLong());
        verify(transport, never()).send(any(), any(), any(), any(), any());
        InOrder inOrder = inOrder(turnDao);
        inOrder.verify(turnDao).acquireConversationLock(argThat(this::isLogicalConversationLock), eq(10));
        inOrder.verify(turnDao).findByExternalMsgId(1L, "msg-q");
        inOrder.verify(turnDao).acquireConversationLock("agent-conversation:1:77", 10);
        inOrder.verify(turnDao).findProcessingInbound(1L, 77L);
        inOrder.verify(turnDao).insert(any());
        inOrder.verify(turnDao).releaseConversationLock(argThat(this::isLogicalConversationLock));
        inOrder.verify(turnDao).releaseConversationLock("agent-conversation:1:77");
    }

    @Test
    void submitTurnQueuesSecondFirstTurnWhenLogicalConversationCreatedDuringLockWait() {
        when(turnDao.findByExternalMsgId(1L, "msg-new")).thenReturn(null);
        AgentConversationDO createdByConcurrentSubmit = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L))
                .thenReturn(createdByConcurrentSubmit);
        AgentConversationTurnDO processing = new AgentConversationTurnDO();
        processing.setId(55L);
        processing.setTenantId(1L);
        processing.setConversationId(77L);
        processing.setDirection("IN");
        processing.setStatus("PROCESSING");
        when(turnDao.findProcessingInbound(1L, 77L)).thenReturn(processing);
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(90L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "second", "msg-new");
        commitTransactionSynchronizations();

        verify(convDao, never()).insert(any());
        verify(turnDao).insert(argThat(t -> "IN".equals(t.getDirection())
                && "second".equals(t.getContent())
                && "QUEUED".equals(t.getStatus())));
        verify(executorSelector, never()).select(anyLong(), nullable(Long.class));
        verify(transport, never()).send(any(), any(), any(), any(), any());
        InOrder inOrder = inOrder(turnDao, convDao);
        inOrder.verify(turnDao).acquireConversationLock(argThat(this::isLogicalConversationLock), eq(10));
        inOrder.verify(convDao).findByKey(1L, "DINGTALK", "conv-x", 3L);
    }

    @Test
    void submitTurnFailsWhenNoExecutorOnline() {
        when(turnDao.findByExternalMsgId(1L, "msg-3")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(null);

        assertThrows(IllegalStateException.class, () ->
                svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "hi", "msg-3"));
        verify(transport, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void acknowledgeTurnResolvesInTurnStoresSessionRefAndDeliversToSink() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");

        verify(sinkRegistry, never()).resolve(anyString());
        verify(sink, never()).deliverReply(any(), anyString(), anyString());
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null);
        verify(convDao).updateCliSessionRef(1L, 77L, "sess-123");
        verify(sink).deliverReply(conv, "reply-md", "msg-55");
        InOrder afterCommitOrder = inOrder(turnDao, sinkRegistry);
        afterCommitOrder.verify(turnDao).releaseConversationLock("agent-conversation:1:77");
        afterCommitOrder.verify(sinkRegistry).resolve("DINGTALK");
    }

    @Test
    void acknowledgeTurnRestoresRequestIdForReplyDeliveryAndOutTurn() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setRequestId("rid-inbound");
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);
        AtomicReference<String> deliveredRequestId = new AtomicReference<>();
        doAnswer(inv -> {
            deliveredRequestId.set(MDC.get("requestId"));
            return null;
        }).when(sink).deliverReply(any(), anyString(), anyString());
        MDC.put("requestId", "rid-before");
        AutoWonderContext.get().setRequestId("rid-before");

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");

        verify(sink, never()).deliverReply(any(), anyString(), anyString());
        commitTransactionSynchronizations();

        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "rid-inbound".equals(t.getRequestId())));
        verify(sink).deliverReply(conv, "reply-md", "msg-55");
        assertEquals("rid-inbound", deliveredRequestId.get());
        assertEquals("rid-before", MDC.get("requestId"));
        assertEquals("rid-before", AutoWonderContext.get().getRequestId());
    }

    @Test
    void acknowledgeTurnDoesNotDeliverReplyOnRollback() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        rollbackTransactionSynchronizations();

        verify(sinkRegistry, never()).resolve(anyString());
        verify(transport, never()).send(any(), any(), any(), any(), any());
        verify(turnDao).releaseConversationLock("agent-conversation:1:77");
    }

    @Test
    void acknowledgeTurnRejectsForeignExecutor() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);

        svc.acknowledgeTurn(1L, 8L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");

        verify(turnDao, never()).updateStatus(anyLong(), anyLong(), any(), any());
        verify(turnDao, never()).insert(any());
        verify(sinkRegistry, never()).resolve(any());
    }

    @Test
    void acknowledgeTurnMarksInTurnFailedAndSkipsDelivery() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L))
                .thenReturn(processingInboundTurn(77L));
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "FAILED", "boom"))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "FAILED", "boom", null, null);
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "FAILED", "boom");
        verify(sinkRegistry, never()).resolve(any());
    }

    @Test
    void acknowledgeTurnPersistsFailureNoticeWhenReplyIsEmpty() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("WORKITEM_CLARIFICATION");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L))
                .thenReturn(processingInboundTurn(77L));
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "FAILED", "boom"))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "FAILED", "boom", null, null);

        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "回复失败：boom".equals(t.getContent())
                && "FAILED".equals(t.getStatus())
                && "boom".equals(t.getError())));
    }

    @Test
    void acknowledgeTurnPersistsPlaceholderWhenReplyAndErrorAreBlank() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("WORKITEM_CLARIFICATION");
        conv.setExecutorId(9L);
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L))
                .thenReturn(processingInboundTurn(77L));
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "  ", null);

        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "（数字人未返回内容）".equals(t.getContent())));
        verify(sinkRegistry, never()).resolve(any());
    }

    @Test
    void acknowledgeDispatchesNextQueuedTurnAfterDelivery() {
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        AgentConversationTurnDO next = new AgentConversationTurnDO();
        next.setId(56L);
        next.setTenantId(1L);
        next.setConversationId(77L);
        next.setDirection("IN");
        next.setContent("next");
        next.setStatus("QUEUED");
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(next);
        when(turnDao.updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null))
                .thenReturn(1);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null);
        verify(turnDao).updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null);
        InOrder inOrder = inOrder(turnDao, transport);
        inOrder.verify(turnDao).updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null);
        inOrder.verify(transport).send(eq(conv), eq(56L), eq("next"), anyString(), any());
    }

    @Test
    void acknowledgeDeliversReplyWhenQueuedDispatchFailsAfterCommit() {
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        AgentConversationTurnDO next = new AgentConversationTurnDO();
        next.setId(56L);
        next.setTenantId(1L);
        next.setConversationId(77L);
        next.setDirection("IN");
        next.setContent("next");
        next.setStatus("QUEUED");
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(next);
        when(turnDao.updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null))
                .thenReturn(1);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);
        doThrow(new RuntimeException("runtime send failed"))
                .when(transport).send(eq(conv), eq(56L), eq("next"), anyString(), any());

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        assertDoesNotThrow(this::commitTransactionSynchronizations);

        verify(sink).deliverReply(conv, "reply-md", "msg-55");
        verify(transport).send(eq(conv), eq(56L), eq("next"), anyString(), any());
        InOrder afterCommitOrder = inOrder(turnDao, sink, transport);
        afterCommitOrder.verify(turnDao).releaseConversationLock("agent-conversation:1:77");
        afterCommitOrder.verify(sink).deliverReply(conv, "reply-md", "msg-55");
        afterCommitOrder.verify(transport).send(eq(conv), eq(56L), eq("next"), anyString(), any());
    }

    @Test
    void acknowledgeSkipsQueuedDispatchWhenPromotionClaimFails() {
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        AgentConversationTurnDO next = new AgentConversationTurnDO();
        next.setId(56L);
        next.setTenantId(1L);
        next.setConversationId(77L);
        next.setDirection("IN");
        next.setContent("next");
        next.setStatus("QUEUED");
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(next);
        when(turnDao.updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null))
                .thenReturn(0);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        verify(turnDao).updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null);
        verify(transport, never()).send(any(), any(), any(), any(), any());
        verify(turnDao).releaseConversationLock("agent-conversation:1:77");
    }

    @Test
    void acknowledgeSkipsReplyAndQueuedDispatchWhenInboundFinalizeClaimFails() {
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(0);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null);
        verify(turnDao, never()).insert(any());
        verify(convDao, never()).updateCliSessionRef(anyLong(), anyLong(), anyString());
        verify(convDao, never()).updateStatusAndLastTurn(anyLong(), anyLong(), anyString(), any());
        verify(turnDao, never()).findNextQueuedInbound(anyLong(), anyLong());
        verify(transport, never()).send(any(), any(), any(), any(), any());
        verify(sinkRegistry, never()).resolve(anyString());
    }

    @Test
    void acknowledgeRejectsProcessingInboundTurnFromDifferentConversation() {
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO otherConversationTurn = new AgentConversationTurnDO();
        otherConversationTurn.setId(55L);
        otherConversationTurn.setTenantId(1L);
        otherConversationTurn.setConversationId(88L);
        otherConversationTurn.setDirection("IN");
        otherConversationTurn.setStatus("PROCESSING");
        otherConversationTurn.setExternalMsgId("msg-other");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(otherConversationTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        verify(turnDao, never()).updateInboundStatusIfProcessing(anyLong(), anyLong(),
                anyLong(), anyString(), any());
        verify(turnDao, never()).insert(any());
        verify(turnDao, never()).findNextQueuedInbound(anyLong(), anyLong());
        verify(transport, never()).send(any(), any(), any(), any(), any());
        verify(sinkRegistry, never()).resolve(anyString());
    }

    @Test
    void submitTurnFailsClearlyWithoutTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
        when(turnDao.findByExternalMsgId(1L, "msg-r")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "more", "msg-r"));

        assertTrue(ex.getMessage().contains("actual transaction required"));
        verify(turnDao, never()).acquireConversationLock(anyString(), anyInt());
    }

    @Test
    void submitTurnFailsClearlyWithoutActualTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        when(turnDao.findByExternalMsgId(1L, "msg-r")).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "more", "msg-r"));

        assertTrue(ex.getMessage().contains("actual transaction required"));
        verify(turnDao, never()).acquireConversationLock(anyString(), anyInt());
    }

    @Test
    void releaseConversationLockAcceptsNonSuccessResultWithoutThrowing() {
        when(turnDao.findByExternalMsgId(1L, "msg-r")).thenReturn(null);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(conv);
        when(turnDao.findProcessingInbound(1L, 77L)).thenReturn(new AgentConversationTurnDO());
        when(turnDao.releaseConversationLock(anyString())).thenReturn(0);

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "queued", "msg-r");
        assertDoesNotThrow(this::commitTransactionSynchronizations);

        verify(turnDao).releaseConversationLock(argThat(this::isLogicalConversationLock));
        verify(turnDao).releaseConversationLock("agent-conversation:1:77");
    }

    @Test
    void recoverStaleTurnsForExecutorRedeliversProcessingTurnAfterActivityReport() {
        Date cutoff = new Date(1_000L);
        AgentConversationDO conv = existingConversationWithSession();
        AgentConversationTurnDO stale = processingInboundTurn(77L);
        stale.setContent("stuck");
        stale.setRequestId("rid-stuck");
        when(turnDao.listStaleProcessingInboundByExecutor(1L, 9L, cutoff, 100))
                .thenReturn(java.util.List.of(stale));
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(stale);

        svc.recoverStaleTurnsForExecutor(1L, 9L, java.util.Set.of(), true, cutoff, 100);
        verify(transport, never()).send(any(), any(), any(), any(), any());
        commitTransactionSynchronizations();

        verify(turnDao).claimStaleDispatchAttemptIfProcessing(1L, 77L, 55L, cutoff);
        verify(transport).send(eq(conv), eq(55L), eq("stuck"), argThat(prompt ->
                prompt != null && prompt.contains("默认数字人")), any());
        verify(turnDao).releaseConversationLock("agent-conversation:1:77");
    }

    @Test
    void recoverInactiveTurnsForReplacedExecutorDoesNotWaitForStaleCutoff() {
        AgentConversationDO conv = existingConversationWithSession();
        AgentConversationTurnDO processing = processingInboundTurn(77L);
        processing.setContent("orphaned by replaced runtime");
        processing.setRequestId("rid-replaced");
        when(turnDao.listStaleProcessingInboundByExecutor(eq(1L), eq(9L), any(Date.class), eq(100)))
                .thenReturn(java.util.List.of(processing));
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(processing);

        svc.recoverInactiveTurnsForReplacedExecutor(1L, 9L, java.util.Set.of());
        commitTransactionSynchronizations();

        verify(turnDao).claimStaleDispatchAttemptIfProcessing(eq(1L), eq(77L), eq(55L), any(Date.class));
        verify(transport).send(eq(conv), eq(55L), eq("orphaned by replaced runtime"),
                anyString(), any());
    }

    @Test
    void recoverStaleTurnsForExecutorSkipsTurnStillActiveInRuntime() {
        Date cutoff = new Date(1_000L);
        AgentConversationDO conv = existingConversationWithSession();
        AgentConversationTurnDO stale = processingInboundTurn(77L);
        when(turnDao.listStaleProcessingInboundByExecutor(1L, 9L, cutoff, 100))
                .thenReturn(java.util.List.of(stale));
        when(convDao.findById(1L, 77L)).thenReturn(conv);

        svc.recoverStaleTurnsForExecutor(1L, 9L, java.util.Set.of(55L), true, cutoff, 100);
        commitTransactionSynchronizations();

        verify(turnDao, never()).claimStaleDispatchAttemptIfProcessing(anyLong(), anyLong(),
                anyLong(), any());
        verify(transport, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void recoverStaleTurnsForExecutorDoesNotSendWhenStaleClaimLosesRace() {
        Date cutoff = new Date(1_000L);
        AgentConversationDO conv = existingConversationWithSession();
        AgentConversationTurnDO stale = processingInboundTurn(77L);
        when(turnDao.listStaleProcessingInboundByExecutor(1L, 9L, cutoff, 100))
                .thenReturn(java.util.List.of(stale));
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(stale);
        when(turnDao.claimStaleDispatchAttemptIfProcessing(1L, 77L, 55L, cutoff)).thenReturn(0);

        svc.recoverStaleTurnsForExecutor(1L, 9L, java.util.Set.of(), true, cutoff, 100);
        commitTransactionSynchronizations();

        verify(transport, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void recoverStaleTurnsFailsAfterBoundedDeliveryAttempts() {
        Date cutoff = new Date(1_000L);
        AgentConversationDO conv = existingConversationWithSession();
        AgentConversationTurnDO stale = processingInboundTurn(77L);
        stale.setDispatchAttempt(3);
        when(turnDao.listStaleProcessingInboundByExecutor(1L, 9L, cutoff, 100))
                .thenReturn(java.util.List.of(stale));
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(stale);
        when(turnDao.updateInboundStatusIfProcessing(eq(1L), eq(77L), eq(55L),
                eq("FAILED"), anyString())).thenReturn(1);

        svc.recoverStaleTurnsForExecutor(1L, 9L, java.util.Set.of(), true, cutoff, 100);
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(eq(1L), eq(77L), eq(55L),
                eq("FAILED"), argThat(error -> error.contains("3 delivery attempts")));
        verify(turnDao, never()).claimStaleDispatchAttemptIfProcessing(anyLong(), anyLong(),
                anyLong(), any());
        verify(transport, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void dingtalkTurnInjectsSenderContextIntoTransportContentButStoresOriginalContent() {
        when(turnDao.findByExternalMsgId(1L, "msg-2")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(9L);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        AgentVersionDO ver = new AgentVersionDO();
        ver.setRoleName("测试数字人");
        when(agentVersionDao.findById(50L)).thenReturn(ver);
        when(convDao.insert(any())).thenAnswer(inv -> {
            AgentConversationDO c = inv.getArgument(0);
            c.setId(77L);
            return 1;
        });
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(88L);
            return 1;
        });

        String sourceContext = """
                {"senderNick":"王五","senderStaffId":"staff-1","senderId":"dt-user-1",
                 "conversationTitle":"需求群","conversationType":"2","sessionWebhook":"secret-url"}
                """;

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "你是谁", "msg-2", sourceContext);
        commitTransactionSynchronizations();

        verify(turnDao).insert(argThat(t -> "IN".equals(t.getDirection())
                && "你是谁".equals(t.getContent())
                && sourceContext.equals(t.getSourceContext())));
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(transport).send(any(), eq(88L), content.capture(), anyString(), any());
        assertTrue(content.getValue().contains("DingTalk message context"));
        assertTrue(content.getValue().contains("Sender nickname: 王五"));
        assertTrue(content.getValue().contains("Sender staffId: staff-1"));
        assertTrue(content.getValue().contains("Sender dingtalk senderId: dt-user-1"));
        assertTrue(content.getValue().contains("Conversation title: 需求群"));
        assertTrue(content.getValue().contains("Do not confuse this sender with any AutoWonder MCP token"));
        assertTrue(content.getValue().contains("User message:\n你是谁"));
        assertFalse(content.getValue().contains("secret-url"));
    }

    @Test
    void invalidDingtalkSourceContextDoesNotBlockDispatch() {
        when(turnDao.findByExternalMsgId(1L, "msg-2")).thenReturn(null);
        when(convDao.findByKey(1L, "DINGTALK", "conv-x", 3L)).thenReturn(null);
        when(executorSelector.select(eq(3L), isNull())).thenReturn(9L);
        AgentDO agent = new AgentDO();
        agent.setId(3L);
        agent.setOnlineVersionId(50L);
        when(agentDao.findById(3L)).thenReturn(agent);
        AgentVersionDO ver = new AgentVersionDO();
        ver.setRoleName("测试数字人");
        when(agentVersionDao.findById(50L)).thenReturn(ver);
        when(convDao.insert(any())).thenAnswer(inv -> {
            AgentConversationDO c = inv.getArgument(0);
            c.setId(77L);
            return 1;
        });
        when(turnDao.insert(any())).thenAnswer(inv -> {
            AgentConversationTurnDO t = inv.getArgument(0);
            t.setId(88L);
            return 1;
        });

        svc.submitTurn(1L, 3L, "DINGTALK", "conv-x", "hello", "msg-2", "{bad-json");
        commitTransactionSynchronizations();

        verify(transport).send(any(), eq(88L), eq("hello"), anyString(), any());
    }

    @Test
    void queuedDingtalkTurnUsesItsOwnSenderContextWhenDispatched() {
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        ConversationChannelSink sink = mock(ConversationChannelSink.class);
        when(sinkRegistry.resolve("DINGTALK")).thenReturn(sink);
        AgentConversationTurnDO next = new AgentConversationTurnDO();
        next.setId(56L);
        next.setTenantId(1L);
        next.setConversationId(77L);
        next.setDirection("IN");
        next.setContent("我是谁");
        next.setSourceContext("{\"senderNick\":\"李四\",\"senderStaffId\":\"staff-2\",\"senderId\":\"dt-user-2\"}");
        next.setStatus("QUEUED");
        when(turnDao.findNextQueuedInbound(1L, 77L)).thenReturn(next);
        when(turnDao.updateStatusIfCurrent(1L, 56L, "QUEUED", "PROCESSING", null))
                .thenReturn(1);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(transport).send(eq(conv), eq(56L), content.capture(), anyString(), any());
        assertTrue(content.getValue().contains("Sender nickname: 李四"));
        assertTrue(content.getValue().contains("Sender staffId: staff-2"));
        assertTrue(content.getValue().contains("User message:\n我是谁"));
    }

    @Test
    void requestTurnCancelSendsCancelFrameForProcessingTurn() {
        ConversationRuntimePresence presence = mock(ConversationRuntimePresence.class);
        when(presence.isExecutorOnline(9L)).thenReturn(true);
        when(presence.supportsProtocolFeature(9L, "CONVERSATION_TURN_CANCEL")).thenReturn(true);
        AgentConversationService presenceSvc = serviceWithPresence(presence);
        presenceSvc.setCancelAckTimeoutSeconds(3600);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(processingInboundTurn(77L));

        presenceSvc.requestTurnCancel(1L, 77L, 55L);

        verify(transport).sendCancel(conv, 55L);
        verify(turnDao, never()).updateInboundStatusIfProcessing(anyLong(), anyLong(), anyLong(),
                anyString(), any());
        verify(turnDao, never()).insert(any());
    }

    @Test
    void requestTurnCancelRejectsWhenRuntimeDoesNotSupportCancelProtocol() {
        ConversationRuntimePresence presence = mock(ConversationRuntimePresence.class);
        when(presence.isExecutorOnline(9L)).thenReturn(true);
        when(presence.supportsProtocolFeature(9L, "CONVERSATION_TURN_CANCEL")).thenReturn(false);
        AgentConversationService presenceSvc = serviceWithPresence(presence);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(processingInboundTurn(77L));

        BizException ex = assertThrows(BizException.class,
                () -> presenceSvc.requestTurnCancel(1L, 77L, 55L));

        assertEquals("10409", ex.getCode());
        verify(transport, never()).sendCancel(any(), anyLong());
    }

    @Test
    void requestTurnCancelFinalizesQueuedTurnWithoutTransport() {
        AgentConversationService cancelSvc = serviceWithPresence(mock(ConversationRuntimePresence.class));
        cancelSvc.setConversationTurnEventService(mock(ConversationTurnEventService.class));
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO queued = processingInboundTurn(77L);
        queued.setStatus("QUEUED");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(queued);
        when(turnDao.updateStatusIfCurrent(1L, 55L, "QUEUED", "CANCELED", null)).thenReturn(1);

        cancelSvc.requestTurnCancel(1L, 77L, 55L);
        commitTransactionSynchronizations();

        verify(turnDao).updateStatusIfCurrent(1L, 55L, "QUEUED", "CANCELED", null);
        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "CANCELED".equals(t.getStatus())
                && "响应已终止".equals(t.getContent())));
        verify(transport, never()).sendCancel(any(), anyLong());
    }

    @Test
    void acknowledgeCanceledTurnPersistsPartialOutputAndPublishesCanceledEvent() {
        ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
        svc.setConversationTurnEventService(eventService);
        svc.setCancelAckTimeoutSeconds(3600);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "CANCELED", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "CANCELED", null, "部分内容", null);
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "CANCELED", null);
        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "CANCELED".equals(t.getStatus())
                && "部分内容".equals(t.getContent())));
        verify(eventService).publishStatusEvent(1L, 77L, 55L, "canceled");
        verify(sinkRegistry, never()).resolve(any());

        // ack 已终结轮次，超时兜底不应再次落库。
        svc.handleCancelAckTimeout(conv, 55L);
        verify(turnDao, times(1)).insert(any());
    }

    @Test
    void acknowledgeCanceledTurnPersistsFallbackWhenPartialOutputIsBlank() {
        ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
        svc.setConversationTurnEventService(eventService);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "CANCELED", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "CANCELED", null, null, null);
        commitTransactionSynchronizations();

        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "CANCELED".equals(t.getStatus())
                && "响应已终止".equals(t.getContent())));
        verify(eventService).publishStatusEvent(1L, 77L, 55L, "canceled");
        verify(sinkRegistry, never()).resolve(any());
    }

    @Test
    void acknowledgeSuccessfulTurnPublishesCompletedStatusEvent() {
        ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
        svc.setConversationTurnEventService(eventService);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        verify(eventService).publishStatusEvent(1L, 77L, 55L, "completed");
    }

    @Test
    void acknowledgeFailedTurnPublishesFailedStatusEvent() {
        ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
        svc.setConversationTurnEventService(eventService);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "FAILED", "boom"))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "FAILED", "boom", null, null);
        commitTransactionSynchronizations();

        verify(eventService).publishStatusEvent(1L, 77L, 55L, "failed");
    }

    @Test
    void acknowledgeTurnSurvivesTerminalStatusEventPublishFailure() {
        ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
        doThrow(new RuntimeException("push down")).when(eventService)
                .publishStatusEvent(anyLong(), anyLong(), anyLong(), anyString());
        svc.setConversationTurnEventService(eventService);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        AgentConversationTurnDO inTurn = processingInboundTurn(77L);
        inTurn.setExternalMsgId("msg-55");
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(inTurn);
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null))
                .thenReturn(1);
        when(turnDao.insert(any())).thenReturn(1);

        svc.acknowledgeTurn(1L, 9L, 77L, 55L, "SUCCESS", null, "reply-md", "sess-123");
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "SUCCESS", null);
        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "SUCCESS".equals(t.getStatus())));
    }

    @Test
    void cancelAckTimeoutFinalizesStillProcessingTurnWithFallbackContent() {
        ConversationTurnEventService eventService = mock(ConversationTurnEventService.class);
        ConversationRuntimePresence presence = mock(ConversationRuntimePresence.class);
        when(presence.isExecutorOnline(9L)).thenReturn(true);
        when(presence.supportsProtocolFeature(9L, "CONVERSATION_TURN_CANCEL")).thenReturn(true);
        AgentConversationService presenceSvc = serviceWithPresence(presence);
        presenceSvc.setConversationTurnEventService(eventService);
        presenceSvc.setCancelAckTimeoutSeconds(3600);
        AgentConversationDO conv = existingConversationWithSession();
        when(convDao.findById(1L, 77L)).thenReturn(conv);
        when(turnDao.findByConversationTurn(1L, 77L, 55L)).thenReturn(processingInboundTurn(77L));
        when(turnDao.updateInboundStatusIfProcessing(1L, 77L, 55L, "CANCELED", null))
                .thenReturn(1);

        presenceSvc.requestTurnCancel(1L, 77L, 55L);
        commitTransactionSynchronizations();
        verify(turnDao, never()).insert(any());

        presenceSvc.handleCancelAckTimeout(conv, 55L);
        commitTransactionSynchronizations();

        verify(turnDao).updateInboundStatusIfProcessing(1L, 77L, 55L, "CANCELED", null);
        verify(turnDao).insert(argThat(t -> "OUT".equals(t.getDirection())
                && "CANCELED".equals(t.getStatus())
                && "响应已终止".equals(t.getContent())));
        verify(eventService).publishStatusEvent(1L, 77L, 55L, "canceled");
    }

    private AgentConversationService serviceWithPresence(ConversationRuntimePresence presence) {
        return new AgentConversationService(convDao, turnDao, transport, executorSelector,
                agentDao, agentVersionDao, sinkRegistry, presence);
    }

    private AgentConversationDO existingConversationWithSession() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setChannel("DINGTALK");
        conv.setChannelConversationId("conv-x");
        conv.setAgentId(3L);
        conv.setAgentVersionId(50L);
        conv.setExecutorId(9L);
        conv.setCliSessionRef("sess-123");
        return conv;
    }

    private AgentConversationTurnDO processingInboundTurn(Long conversationId) {
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(55L);
        turn.setTenantId(1L);
        turn.setConversationId(conversationId);
        turn.setDirection("IN");
        turn.setStatus("PROCESSING");
        return turn;
    }

    private boolean isLogicalConversationLock(String lockName) {
        return lockName != null
                && lockName.startsWith("agent-conv-key:")
                && lockName.length() <= 64;
    }

    private void commitTransactionSynchronizations() {
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private void rollbackTransactionSynchronizations() {
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
