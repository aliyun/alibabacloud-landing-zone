package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationElicitationServiceTest {

    private AgentConversationElicitationDao dao;
    private AgentConversationDao convDao;
    private ConversationTransport transport;
    private ConversationRuntimePresence runtimePresence;
    private AgentConversationTurnDao turnDao;
    private ConversationBrowserEventPublisher browserEventPublisher;
    private ConversationElicitationService service;

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"accept","decline"})
    void legacyHumanAdmissionReleasesRowLocksBeforeTransport(String action) {
        var record=pending("human",100L);
        when(dao.findByRequestId(1,10,"human")).thenReturn(record);
        when(dao.settleIfPending(eq(1L),eq(10L),eq("human"),anyString(),any())).thenReturn(1);
        var locked=new java.util.concurrent.atomic.AtomicBoolean();
        var manager=new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){locked.set(true);}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){locked.set(false);}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){locked.set(false);}
        };
        service.configureLegacyAnswerTransaction(manager);
        org.mockito.Mockito.doAnswer(invocation->{org.junit.jupiter.api.Assertions.assertFalse(locked.get());return null;})
                .when(transport).sendElicitationReply(any(),eq(100L),eq("human"),eq(action),any());
        service.reply(1,10,"human",action,"{}");
        verify(transport).sendElicitationReply(any(),eq(100L),eq("human"),eq(action),any());
    }

    @Test void failedLegacySendAfterExpiryCannotRestorePending() {
        var record=pending("human",100L);when(dao.findByRequestId(1,10,"human")).thenReturn(record);
        when(dao.settleIfPending(1,10,"human","ANSWERED","{}")).thenReturn(1);
        var active=turn("IN","PROCESSING");active.setDispatchAttempt(2);
        when(turnDao.findByConversationTurn(1L,10L,100L)).thenReturn(active);
        org.mockito.Mockito.doAnswer(invocation->{
            active.setSourceContext("{\"questionExpiryStop\":{\"attempt\":2,\"executorId\":9,\"requestedAt\":1}}");
            throw new IllegalStateException("wire failed");
        }).when(transport).sendElicitationReply(any(),eq(100L),eq("human"),eq("accept"),eq("{}"));
        assertThrows(IllegalStateException.class,()->service.reply(1,10,"human","accept","{}"));
        verify(dao,never()).restorePendingIfStatus(anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void negotiatedQuestionRecordsDeliveryIdentityFromCurrentTurn() {
        AgentConversationTurnDO active = turn("IN", "PROCESSING");
        active.setDispatchAttempt(3);
        when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(active);
        service.onEvent(1, 10, 100, "acp_elicitation",
                openedPayload("r1").replace("\"mode\":", "\"answerProtocolVersion\":1,\"mode\":"));
        ArgumentCaptor<AgentConversationElicitationDO> saved = ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao).insertIfAbsent(saved.capture());
        assertEquals(1, saved.getValue().getAnswerProtocolVersion());
        assertEquals(3, saved.getValue().getDispatchAttempt());
        assertEquals(9L, saved.getValue().getExecutorId());
    }

    @Test
    void negotiatedExpiryDelegatesToSystemStopCoordinator() {
        AgentConversationElicitationDO question = new AgentConversationElicitationDO();
        question.setTenantId(1L);question.setConversationId(10L);question.setTurnId(100L);
        question.setId(1L);question.setRequestId("expired");question.setAnswerProtocolVersion(1);
        when(dao.listPendingOlderThan(any(),anyLong(),anyInt())).thenReturn(List.of(question));
        var delivery=mock(ConversationAnswerDeliveryService.class);
        when(delivery.expireQuestion(eq(question),any())).thenReturn(true);
        service.setAnswerDeliveryService(delivery);
        service.expirePending(new Date(),100);
        verify(delivery).expireQuestion(eq(question),any());
        verify(transport,never()).sendElicitationReply(any(),anyLong(),anyString(),anyString(),any());
    }

    @Test
    void legacyReplyEntryCannotBypassPersistedActor() {
        var record = new AgentConversationElicitationDO();
        record.setTurnId(100L); record.setStatus("PENDING"); record.setRequestId("actor-card");
        when(dao.findByRequestId(1L, 10L, "actor-card")).thenReturn(record);
        var active = turn("IN", "PROCESSING"); active.setSubmittedUserId(70L);
        when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(active);
        assertThrows(BizException.class, () -> service.reply(1L, 10L, "actor-card", "accept", "{}"));
        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void negotiatedCardRejectsDifferentUserBeforeDurableAnswerAndKeepsOriginalActor() {
        var record = new AgentConversationElicitationDO();
        record.setTurnId(100L); record.setStatus("PENDING"); record.setRequestId("actor-card"); record.setAnswerProtocolVersion(1);
        when(dao.findByRequestId(1L, 10L, "actor-card")).thenReturn(record);
        var active = turn("IN", "PROCESSING"); active.setSubmittedUserId(70L);
        when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(active);
        var delivery = mock(ConversationAnswerDeliveryService.class); service.setAnswerDeliveryService(delivery);
        assertThrows(BizException.class, () -> service.reply(1L, 10L, "actor-card", "accept", "{}", 71L));
        verify(delivery, never()).submit(anyLong(), anyLong(), anyString(), anyString(), any());
        service.reply(1L, 10L, "actor-card", "accept", "{}", 70L);
        verify(delivery).submit(1L, 10L, "actor-card", "accept", "{}");
        assertEquals(70L, active.getSubmittedUserId());
    }

    @BeforeEach
    void setUp() {
        dao = mock(AgentConversationElicitationDao.class);
        convDao = mock(AgentConversationDao.class);
        transport = mock(ConversationTransport.class);
        runtimePresence = mock(ConversationRuntimePresence.class);
        turnDao = mock(AgentConversationTurnDao.class);
        browserEventPublisher = mock(ConversationBrowserEventPublisher.class);
        service = new ConversationElicitationService(dao, convDao, transport, runtimePresence,
                turnDao);
        service.setBrowserEventPublisher(browserEventPublisher);
        when(convDao.findById(1L, 10L)).thenReturn(conversation());
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(true);
        when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(turn("IN", "PROCESSING"));
        when(turnDao.findByConversationTurnForUpdate(anyLong(),anyLong(),anyLong())).thenAnswer(invocation ->
                turnDao.findByConversationTurn(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2)));
    }

    private AgentConversationTurnDO turn(String direction, String status) {
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(100L);
        turn.setTenantId(1L);
        turn.setConversationId(10L);
        turn.setDirection(direction);
        turn.setStatus(status);
        return turn;
    }

    private AgentConversationDO conversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(10L);
        conv.setTenantId(1L);
        conv.setExecutorId(9L);
        return conv;
    }

    private String openedPayload(String requestId) {
        return "{\"type\":\"acp_elicitation\",\"data\":{\"requestId\":\"" + requestId
                + "\",\"mode\":\"form\",\"message\":\"pick\",\"toolCallId\":\"call_1\","
                + "\"requestedSchema\":{\"type\":\"object\"}}}";
    }

    /**
     * 一次提问携带多个问题的载荷形状：题目整体放在 requestedSchema.properties 这个
     * JSON 对象里，键序就是用户应当看到的题目顺序（表上没有 order/seq 列）。
     */
    private String multiQuestionPayload(String requestId) {
        return "{\"type\":\"acp_elicitation\",\"data\":{\"requestId\":\"" + requestId
                + "\",\"mode\":\"form\",\"message\":\"请确认以下问题\","
                + "\"requestedSchema\":{\"type\":\"object\",\"title\":\"需求澄清\","
                + "\"properties\":{"
                + "\"verify\":{\"type\":\"string\",\"title\":\"验证方式\"},"
                + "\"collab\":{\"type\":\"string\",\"title\":\"协同模式\"},"
                + "\"deploy\":{\"type\":\"string\",\"title\":\"部署环境\"},"
                + "\"scope\":{\"type\":\"string\",\"title\":\"影响范围\"},"
                + "\"owner\":{\"type\":\"string\",\"title\":\"负责人\"}},"
                + "\"required\":[\"verify\"]}}}";
    }

    /**
     * 断言落库原文里的题目键序。刻意不用解析器读回来比对：解析器自身的重排会把问题
     * 掩盖掉，而库里这段文本才是刷新 / 恢复路径唯一能拿到的顺序信息。
     */
    private static void assertQuestionOrder(String schemaJson, String... expected) {
        int previous = -1;
        for (String key : expected) {
            int at = schemaJson.indexOf("\"" + key + "\":");
            assertTrue(at > previous, "题目 " + key + " 的顺序不对: " + schemaJson);
            previous = at;
        }
    }

    private String captureOpenedSchemaJson() {
        ArgumentCaptor<AgentConversationElicitationDO> captor =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao).insertIfAbsent(captor.capture());
        return captor.getValue().getSchemaJson();
    }

    /** 调用方的两步契约：事务内落状态，提交之后才发副作用。 */
    private void cancelTurn(ConversationElicitationService target) {
        target.notifyCanceled(target.settlePendingForTurn(1L, 10L, 100L));
    }

    private AgentConversationElicitationDO pending(String requestId, long turnId) {
        AgentConversationElicitationDO record = new AgentConversationElicitationDO();
        record.setTenantId(1L);
        record.setConversationId(10L);
        record.setTurnId(turnId);
        record.setId(turnId);
        record.setRequestId(requestId);
        record.setMode("form");
        record.setStatus("PENDING");
        return record;
    }

    /** S1：上行事件应落一条 PENDING 记录，schema 原样存储。 */
    @Test
    void opensPendingRecordFromEvent() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation", openedPayload("req-1"));

        ArgumentCaptor<AgentConversationElicitationDO> captor =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao).insertIfAbsent(captor.capture());
        AgentConversationElicitationDO saved = captor.getValue();
        assertEquals("req-1", saved.getRequestId());
        assertEquals("PENDING", saved.getStatus());
        assertEquals("form", saved.getMode());
        assertEquals("pick", saved.getMessage());
        assertEquals(1L, saved.getTenantId());
        assertEquals(10L, saved.getConversationId());
        assertEquals(100L, saved.getTurnId());
        assertTrue(saved.getSchemaJson().contains("\"type\":\"object\""));
    }

    /**
     * 工单 53308：一次提问的多个题目必须按数字员工发出的顺序落库。表上没有
     * order/seq 列，schema_json 的键序是刷新 / 恢复路径唯一的顺序来源，键序一乱
     * 用户看到的题目就是乱的。
     */
    @Test
    void persistsMultiQuestionSchemaInSentOrder() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation", multiQuestionPayload("req-1"));

        assertQuestionOrder(captureOpenedSchemaJson(),
                "verify", "collab", "deploy", "scope", "owner");
    }

    /**
     * 键序还必须稳定：哈希桶序会随键集合与插入历史变化，同一份载荷在不同时刻落库
     * 可能得到不同顺序，用户每次刷新看到的题目顺序也就不一样。
     */
    @Test
    void persistsSameQuestionOrderOnEveryDelivery() {
        for (int i = 0; i < 20; i++) {
            service.onEvent(1L, 10L, 100L, "acp_elicitation", multiQuestionPayload("req-" + i));
        }

        ArgumentCaptor<AgentConversationElicitationDO> captor =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao, times(20)).insertIfAbsent(captor.capture());
        for (AgentConversationElicitationDO saved : captor.getAllValues()) {
            assertQuestionOrder(saved.getSchemaJson(),
                    "verify", "collab", "deploy", "scope", "owner");
        }
    }

    /** 单问题卡片是既有主路径，保序改动不能让它的 schema 落库变形。 */
    @Test
    void persistsSingleQuestionSchemaUnchanged() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation",
                "{\"type\":\"acp_elicitation\",\"data\":{\"requestId\":\"req-1\",\"mode\":\"form\","
                        + "\"message\":\"pick\",\"requestedSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"only\":{\"type\":\"string\",\"title\":\"验证方式\"}}}}}");

        String schemaJson = captureOpenedSchemaJson();
        assertQuestionOrder(schemaJson, "only");
        assertTrue(schemaJson.contains("\"type\":\"object\""), schemaJson);
        assertTrue(schemaJson.contains("\"title\":\"验证方式\""), schemaJson);
    }

    /** mode 缺失或为空白时兜底为 form：库里的列 NOT NULL，插空值会直接报错。 */
    @Test
    void opensWithDefaultModeWhenRuntimeOmitsIt() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation",
                "{\"data\":{\"requestId\":\"req-1\",\"requestedSchema\":{}}}");
        service.onEvent(1L, 10L, 100L, "acp_elicitation",
                "{\"data\":{\"requestId\":\"req-2\",\"mode\":\"  \"}}");

        ArgumentCaptor<AgentConversationElicitationDO> captor =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao, times(2)).insertIfAbsent(captor.capture());
        assertEquals("form", captor.getAllValues().get(0).getMode());
        assertEquals("form", captor.getAllValues().get(1).getMode());
    }

    /** message 列是 VARCHAR(1024)，Agent 的提问正文长度不可控，必须截断否则插入直接失败。 */
    @Test
    void truncatesOverlongMessageToFitColumn() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation",
                "{\"data\":{\"requestId\":\"req-1\",\"message\":\"" + "x".repeat(2000) + "\"}}");

        ArgumentCaptor<AgentConversationElicitationDO> captor =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(dao).insertIfAbsent(captor.capture());
        assertEquals(1024, captor.getValue().getMessage().length());
    }

    /** S2：分片重投导致同一事件重复到达时必须幂等。 */
    @Test
    void openIsIdempotentAcrossRedelivery() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation", openedPayload("req-1"));
        service.onEvent(1L, 10L, 100L, "acp_elicitation", openedPayload("req-1"));

        // 幂等由 DAO 的 INSERT IGNORE + 唯一键保证，service 不做额外查询，
        // 避免在事件热路径上多一次 DB 往返。
        verify(dao, times(2)).insertIfAbsent(any());
        verify(dao, never()).findByRequestId(anyLong(), anyLong(), anyString());
    }

    /** S3：执行器回传的 resolved 事件应把记录推到终态。 */
    @Test
    void resolvedEventSettlesRecord() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation_resolved",
                "{\"type\":\"acp_elicitation_resolved\",\"data\":{\"requestId\":\"req-1\","
                        + "\"action\":\"accept\"}}");

        // answerJson 传 null：答案已在 reply() 落库，resolved 只是执行器的确认回声，
        // 不能反过来把库里的答案抹掉（抢不到 PENDING 转移时天然不会覆盖）。
        verify(dao).settleIfPending(eq(1L), eq(10L), eq("req-1"), eq("ANSWERED"), isNull());
    }

    @Test
    void resolvedEventMapsDeclineAndCancelToTheirOwnTerminalStatus() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation_resolved",
                "{\"data\":{\"requestId\":\"req-1\",\"action\":\"decline\"}}");
        service.onEvent(1L, 10L, 100L, "acp_elicitation_resolved",
                "{\"data\":{\"requestId\":\"req-2\",\"action\":\"cancel\"}}");

        verify(dao).settleIfPending(1L, 10L, "req-1", "DECLINED", null);
        verify(dao).settleIfPending(1L, 10L, "req-2", "CANCELED", null);
    }

    @Test
    void resolvedEventWithUnknownActionIsIgnored() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation_resolved",
                "{\"data\":{\"requestId\":\"req-1\",\"action\":\"weird\"}}");

        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    /** 事件热路径不能因一条脏数据中断整条事件流，只记 warn。 */
    @Test
    void malformedEventPayloadIsToleratedWithoutThrowing() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation", "not json at all");
        service.onEvent(1L, 10L, 100L, "acp_elicitation", "{\"type\":\"acp_elicitation\"}");
        service.onEvent(1L, 10L, 100L, "acp_elicitation", "{\"data\":{\"mode\":\"form\"}}");
        service.onEvent(1L, 10L, 100L, "acp_elicitation", "{\"data\":{\"requestId\":\"  \"}}");
        service.onEvent(1L, 10L, 100L, "acp_elicitation", null);

        verify(dao, never()).insertIfAbsent(any());
        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    /** acp_elicitation 前缀下未来可能出现新事件，不认识就放过，别猜语义。 */
    @Test
    void unknownElicitationEventTypeIsIgnored() {
        service.onEvent(1L, 10L, 100L, "acp_elicitation_future",
                "{\"data\":{\"requestId\":\"req-1\"}}");

        verify(dao, never()).insertIfAbsent(any());
        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    /** S4：回答提交落 answer 并下发 REPLY 帧。 */
    @Test
    void replyPersistsAnswerAndDispatchesFrame() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}");

        verify(dao).settleIfPending(1L, 10L, "req-1", "ANSWERED", "{\"q0\":\"A\"}");
        verify(transport).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-1"), eq("accept"), eq("{\"q0\":\"A\"}"));
    }

    /** S5：非 PENDING 记录拒绝回答，且不下发帧（否则执行器会收到孤儿答案）。 */
    @Test
    void replyRejectsNonPendingRecord() {
        AgentConversationElicitationDO answered = pending("req-1", 100L);
        answered.setStatus("ANSWERED");
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(answered);

        assertThrows(BizException.class, () -> service.reply(1L, 10L, "req-1", "accept", "{}"));
        verifyNoFrameSent();
    }

    /** S6：requestId 不属于该会话时拒绝（findByRequestId 按 conversationId 收口）。 */
    @Test
    void replyRejectsUnknownRequest() {
        when(dao.findByRequestId(1L, 10L, "nope")).thenReturn(null);

        assertThrows(BizException.class, () -> service.reply(1L, 10L, "nope", "accept", "{}"));
        verifyNoFrameSent();
    }

    /** S7：执行器离线时先拒绝，避免把卡片改成 ANSWERED 却没人收到答案。 */
    @Test
    void replyRejectsWhenRuntimeOffline() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(runtimePresence.isExecutorOnline(9L)).thenReturn(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));

        assertEquals("RUNTIME_OFFLINE", failure.getMessage());
        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoFrameSent();
    }

    /** S11 的回答侧对偶：抢不到状态转移说明过期任务先到，不能再下发帧。 */
    @Test
    void replyRejectsWhenStateTransitionLostToConcurrentSettle() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));
        verifyNoFrameSent();
    }

    /**
     * 投递失败必须把状态补偿回 PENDING 并把异常抛给调用方：否则卡片停在
     * ANSWERED，用户拿到错误却再也无法重答，答案永久丢失。
     */
    @Test
    void replyRestoresPendingWhenDeliveryFails() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("RUNTIME_OFFLINE"))
                .when(transport).sendElicitationReply(any(), anyLong(), anyString(), anyString(), any());

        assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));

        verify(dao).restorePendingIfStatus(1L, 10L, "req-1", "ANSWERED");
    }

    /** 补偿回 PENDING 之后必须真的能再答一次，否则补偿没有意义。 */
    @Test
    void replySucceedsOnRetryAfterDeliveryFailureRestoredPending() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(dao.restorePendingIfStatus(1L, 10L, "req-1", "ANSWERED")).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("RUNTIME_OFFLINE"))
                .doNothing()
                .when(transport).sendElicitationReply(any(), anyLong(), anyString(), anyString(), any());

        assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));
        service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}");

        verify(dao, times(2)).settleIfPending(1L, 10L, "req-1", "ANSWERED", "{\"q0\":\"A\"}");
        verify(transport, times(2)).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-1"), eq("accept"), eq("{\"q0\":\"A\"}"));
    }

    /**
     * 轮次已结束时执行器侧的挂起 JSON-RPC 请求早已消失，答案会进黑洞，
     * 必须在落终态前就拒绝。
     */
    @Test
    void replyRejectsWhenOwningTurnIsNoLongerProcessing() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        for (String status : List.of("SUCCESS", "FAILED", "CANCELED", "QUEUED")) {
            when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(turn("IN", status));
            assertThrows(BizException.class,
                    () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"),
                    "应拒绝 " + status);
        }

        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoFrameSent();
    }

    @Test
    void replyRejectsWhenOwningTurnIsGone() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(null);

        assertThrows(BizException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));

        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoFrameSent();
    }

    @Test
    void replyRejectsUnsupportedAction() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));

        // cancel 是服务端内部的取消联动动作，不接受用户直接提交。
        assertThrows(BizException.class, () -> service.reply(1L, 10L, "req-1", "cancel", null));
        assertThrows(BizException.class, () -> service.reply(1L, 10L, "req-1", "", null));
        verifyNoFrameSent();
    }

    /** accept 必须带答案，否则执行器会把空表单交回 Agent。 */
    @Test
    void replyRejectsAcceptWithoutContent() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));

        assertThrows(BizException.class, () -> service.reply(1L, 10L, "req-1", "accept", null));
        assertThrows(BizException.class, () -> service.reply(1L, 10L, "req-1", "accept", "  "));
        verifyNoFrameSent();
    }

    @Test
    void replyRejectsNonObjectContent() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));

        // content 必须是 JSON 对象：帧构造要把它放进 content 键，数组、裸标量、
        // 字面量 null 与畸形串都会在投递时炸掉，那时卡片已被改成 ANSWERED。
        for (String bad : List.of("[1,2]", "\"just a string\"", "42", "null", "{oops")) {
            assertThrows(BizException.class,
                    () -> service.reply(1L, 10L, "req-1", "accept", bad), "应拒绝 " + bad);
        }
        verifyNoFrameSent();
    }

    /** 会话已被删或从未绑定执行器时，答案无处可去，按离线处理。 */
    @Test
    void replyRejectsWhenConversationOrExecutorMissing() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(convDao.findById(1L, 10L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));

        AgentConversationDO unbound = new AgentConversationDO();
        unbound.setId(10L);
        unbound.setTenantId(1L);
        when(convDao.findById(1L, 10L)).thenReturn(unbound);

        assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 10L, "req-1", "accept", "{\"q0\":\"A\"}"));

        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoFrameSent();
    }

    /** S8：decline 不携带 answer。 */
    @Test
    void declineCarriesNoAnswer() {
        when(dao.findByRequestId(1L, 10L, "req-1")).thenReturn(pending("req-1", 100L));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        service.reply(1L, 10L, "req-1", "decline", "{\"q0\":\"ignored\"}");

        verify(dao).settleIfPending(1L, 10L, "req-1", "DECLINED", null);
        verify(transport).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-1"), eq("decline"), isNull());
    }

    /** S9：轮次取消时所有 PENDING 卡片都要收到 cancel，ACP 要求挂起请求必须有终态。 */
    @Test
    void cancelTurnCancelsEveryPendingCard() {
        when(dao.listPendingByTurn(1L, 100L))
                .thenReturn(List.of(pending("req-1", 100L), pending("req-2", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        cancelTurn(service);

        verify(dao).settleIfPending(1L, 10L, "req-1", "CANCELED", null);
        verify(dao).settleIfPending(1L, 10L, "req-2", "CANCELED", null);
        verify(transport).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-1"), eq("cancel"), isNull());
        verify(transport).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-2"), eq("cancel"), isNull());
    }

    @Test
    void cancelTurnSkipsCardsLostToConcurrentReply() {
        when(dao.listPendingByTurn(1L, 100L)).thenReturn(List.of(pending("req-1", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(0);

        cancelTurn(service);

        verifyNoFrameSent();
    }

    /** 取消路径不能因为一张卡片投递失败就放弃剩下的：轮次取消随后就会执行。 */
    @Test
    void cancelTurnContinuesAfterOneDeliveryFailure() {
        when(dao.listPendingByTurn(1L, 100L))
                .thenReturn(List.of(pending("req-1", 100L), pending("req-2", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("ws down"))
                .when(transport).sendElicitationReply(any(), eq(100L), eq("req-1"), anyString(), any());

        cancelTurn(service);

        verify(transport).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-2"), eq("cancel"), isNull());
    }

    @Test
    void cancelTurnIsNoopWhenNothingPending() {
        when(dao.listPendingByTurn(1L, 100L)).thenReturn(List.of());

        cancelTurn(service);

        verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(convDao, never()).findById(anyLong(), anyLong());
    }

    /**
     * 状态转移必须能单独跑而不发任何不可回滚的副作用 —— 调用方在事务里调它，
     * 事务回滚后卡片回到 PENDING；若这里已经把 cancel 帧发给了执行器，用户之后
     * 的回答会被服务端接受却永远投不进去。
     */
    @Test
    void settlePendingForTurnWritesStateWithoutSideEffects() {
        when(dao.listPendingByTurn(1L, 100L)).thenReturn(List.of(pending("req-1", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        List<AgentConversationElicitationDO> settled = service.settlePendingForTurn(1L, 10L, 100L);

        assertEquals(1, settled.size());
        assertEquals("req-1", settled.get(0).getRequestId());
        verify(dao).settleIfPending(1L, 10L, "req-1", "CANCELED", null);
        verifyNoFrameSent();
        verify(browserEventPublisher, never()).publishServerEvent(anyLong(), anyLong(), anyLong(),
                anyString(), anyString());
    }

    /** 只有抢到状态转移的才进返回值，否则提交后会给已作答的卡片补发一个矛盾动作。 */
    @Test
    void settlePendingForTurnExcludesCardsLostToConcurrentReply() {
        when(dao.listPendingByTurn(1L, 100L))
                .thenReturn(List.of(pending("req-1", 100L), pending("req-2", 100L)));
        when(dao.settleIfPending(1L, 10L, "req-1", "CANCELED", null)).thenReturn(0);
        when(dao.settleIfPending(1L, 10L, "req-2", "CANCELED", null)).thenReturn(1);

        List<AgentConversationElicitationDO> settled = service.settlePendingForTurn(1L, 10L, 100L);

        assertEquals(1, settled.size());
        assertEquals("req-2", settled.get(0).getRequestId());
    }

    @Test
    void legacyExpiryDelegatesToTheSameCoordinatorWithoutAnAnswer() {
        var question = pending("req-1",100L);
        when(dao.listPendingOlderThan(any(Date.class),anyLong(),anyInt())).thenReturn(List.of(question));
        var delivery=mock(ConversationAnswerDeliveryService.class);
        service.setAnswerDeliveryService(delivery);
        service.expirePending(new Date(),50);
        verify(delivery).expireQuestion(eq(question),any());
        verifyNoFrameSent();
    }

    @Test
    void fullPageOfUnprovableCandidatesDoesNotStarveLaterDueQuestion() {
        var all=new java.util.ArrayList<AgentConversationElicitationDO>();
        for(long id=1;id<=201;id++) { var q=pending("q"+id,id);q.setId(id);all.add(q); }
        when(dao.listPendingOlderThan(any(),anyLong(),anyInt())).thenAnswer(invocation->all.stream().filter(q->q.getId()>(long)invocation.getArgument(1)).limit((int)invocation.getArgument(2)).toList());
        var delivery=mock(ConversationAnswerDeliveryService.class);
        service.setAnswerDeliveryService(delivery);
        service.expirePending(new Date(),200);service.expirePending(new Date(),200);
        verify(delivery).expireQuestion(eq(all.get(200)),any());
        service.expirePending(new Date(),200);
        verify(delivery,times(2)).expireQuestion(eq(all.getFirst()),any());
        verify(dao,times(2)).listPendingOlderThan(any(),eq(0L),eq(200));
        verify(dao).listPendingOlderThan(any(),eq(200L),eq(200));
    }

    @Test
    void expiryIsNoopWhenNothingStale() {
        when(dao.listPendingOlderThan(any(Date.class),anyLong(),anyInt())).thenReturn(List.of());
        service.expirePending(new Date(),50);
        verify(dao,never()).settleIfPending(anyLong(),anyLong(),anyString(),anyString(),any());
    }

    @Test
    void expiryFailureDoesNotSkipRemainingCandidates() {
        var first=pending("first",100L); var second=pending("second",200L);
        when(dao.listPendingOlderThan(any(),anyLong(),anyInt())).thenReturn(List.of(first,second));
        var delivery=mock(ConversationAnswerDeliveryService.class);
        when(delivery.expireQuestion(eq(first),any())).thenThrow(new IllegalStateException("retry later"));
        service.setAnswerDeliveryService(delivery);
        service.expirePending(new Date(),50);
        verify(delivery).expireQuestion(eq(second),any()); verifyNoFrameSent();
    }

    @Test
    void expiryPushesTruthfulStatusToBrowserWithoutRuntimeAnswer() {
        var expired=pending("req-1",100L); expired.setStatus("EXPIRED");
        service.notifySystemStopped(List.of(expired));
        ArgumentCaptor<String> payload=ArgumentCaptor.forClass(String.class);
        verify(browserEventPublisher).publishCommittedServerEvent(eq(1L),eq(10L),eq(100L),eq("acp_elicitation_resolved"),payload.capture());
        assertTrue(payload.getValue().contains("\"status\":\"EXPIRED\""));
        assertTrue(payload.getValue().contains("\"action\":\"cancel\""));
        verifyNoFrameSent();
    }

    @Test
    void cancelPushesResolvedEventToBrowser() {
        when(dao.listPendingByTurn(1L, 100L)).thenReturn(List.of(pending("req-1", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        cancelTurn(service);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(browserEventPublisher).publishServerEvent(eq(1L), eq(10L), eq(100L),
                eq("acp_elicitation_resolved"), payload.capture());
        assertTrue(payload.getValue().contains("\"action\":\"cancel\""), payload.getValue());
    }

    /** 抢不到状态转移说明别人已经把卡片终结了，不能再推一条矛盾的终态。 */
    @Test
    void noBrowserPushWhenTransitionLostToConcurrentWinner() {
        when(dao.listPendingByTurn(1L, 100L)).thenReturn(List.of(pending("req-1", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(0);

        cancelTurn(service);

        verify(browserEventPublisher, never()).publishServerEvent(anyLong(), anyLong(), anyLong(),
                anyString(), anyString());
    }

    /** 推浏览器失败不该让剩下的卡片和取消流程停摆。 */
    @Test
    void browserPushFailureDoesNotStopRemainingCards() {
        when(dao.listPendingByTurn(1L, 100L))
                .thenReturn(List.of(pending("req-1", 100L), pending("req-2", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("publisher down"))
                .when(browserEventPublisher).publishServerEvent(anyLong(), anyLong(), anyLong(),
                        anyString(), anyString());

        cancelTurn(service);

        verify(transport).sendElicitationReply(any(AgentConversationDO.class), eq(100L),
                eq("req-2"), eq("cancel"), isNull());
    }

    /** 事件服务是可选注入（打破与 ConversationTurnEventService 的循环依赖）。 */
    @Test
    void terminalStateSurvivesWithoutAnEventService() {
        ConversationElicitationService bare = new ConversationElicitationService(dao, convDao,
                transport, runtimePresence, turnDao);
        when(dao.listPendingByTurn(1L, 100L)).thenReturn(List.of(pending("req-1", 100L)));
        when(dao.settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        cancelTurn(bare);

        verify(dao).settleIfPending(1L, 10L, "req-1", "CANCELED", null);
    }

    /** 刷新页面后要能恢复未解决卡片（F16 的服务端一侧）。 */
    @Test
    void listPendingDelegatesToDao() {
        when(dao.listPendingByConversation(1L, 10L)).thenReturn(List.of(pending("req-1", 100L)));

        List<AgentConversationElicitationDO> rows = service.listPending(1L, 10L);

        assertEquals(1, rows.size());
        assertEquals("req-1", rows.get(0).getRequestId());
    }

    private void verifyNoFrameSent() {
        verify(transport, never()).sendElicitationReply(any(), anyLong(), anyString(),
                anyString(), any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "settled", "action", "blank", "object", "turn", "concurrent"})
    void ownedGuardLocalizesWithoutChangingRawCodeCauseOrWriteOrder(String branch) {
        String requestId = "<b>raw-id</b>";
        AgentConversationElicitationDO card = pending(requestId, 100L);
        if ("settled".equals(branch)) card.setStatus("ANSWERED");
        if (!"missing".equals(branch)) when(dao.findByRequestId(1L, 10L, requestId)).thenReturn(card);
        if ("turn".equals(branch)) when(turnDao.findByConversationTurn(1L, 10L, 100L)).thenReturn(null);
        String action = "action".equals(branch) ? "<raw>" : "accept";
        String answer = "blank".equals(branch) ? " " : "object".equals(branch) ? "[]" : "{}";
        BizException error = assertThrows(BizException.class, () -> service.reply(1L, 10L, requestId, action, answer));
        String raw = switch(branch) {
            case "missing" -> "elicitation not found in this conversation: " + requestId;
            case "settled" -> "elicitation already settled: ANSWERED";
            case "action" -> "unsupported elicitation action: <raw>";
            case "blank" -> "elicitation answer is required";
            case "object" -> "elicitation answer must be a JSON object";
            case "turn" -> "elicitation turn is no longer processing: 100";
            default -> "elicitation was settled concurrently: " + requestId;
        };
        assertEquals(raw, error.getMessage());
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        try {
            var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error);
            assertEquals("missing".equals(branch) ? 200
                    : java.util.Set.of("action", "blank", "object").contains(branch) ? 400 : 409,
                    response.getStatusCodeValue());
            assertEquals(switch(branch) {
                case "missing" -> "Question not found in this conversation: " + requestId;
                case "settled" -> "This question has already been settled: ANSWERED";
                case "action" -> "Unsupported question action: <raw>";
                case "blank" -> "An answer is required.";
                case "object" -> "The answer must be a JSON object.";
                case "turn" -> "The question's turn is no longer processing: 100";
                default -> "This question was settled concurrently: " + requestId;
            }, response.getBody().getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            assertEquals(switch(branch) {
                case "missing" -> "此会话中不存在该问题：" + requestId;
                case "settled" -> "此问题已结算：ANSWERED";
                case "action" -> "不支持的问题操作：<raw>";
                case "blank" -> "回答不能为空。";
                case "object" -> "回答必须是 JSON 对象。";
                case "turn" -> "问题所属轮次已不再处理中：100";
                default -> "此问题已被并发结算：" + requestId;
            }, new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error).getBody().getMessage());

        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
        verify(transport, never()).sendElicitationReply(any(), anyLong(), anyString(), anyString(), any());
        if (!"concurrent".equals(branch)) verify(dao, never()).settleIfPending(anyLong(), anyLong(), anyString(), anyString(), any());
    }

}
