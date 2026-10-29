package com.aliyun.autowonder.conversation;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 基于 H2 的挂起卡片 DAO 回归测试。三条断言对应三个真实故障模式：
 * 事件分片重投导致重复插入、回答与过期竞争同一次状态转移、过期扫描误伤终态记录。
 */
class AgentConversationElicitationDaoTest {

    static final long TENANT = 1L;
    static final long CONVERSATION = 10L;
    static final long TURN = 100L;
    static final String JDBC_URL =
            "jdbc:h2:mem:conversation_elicitation_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static AgentConversationElicitationDao dao;

    @Test
    void negotiatedQuestionIdentitySurvivesReload() {
        AgentConversationElicitationDO row = pending("delivery");
        row.setAnswerProtocolVersion(1);
        row.setDispatchAttempt(4);
        row.setExecutorId(9L);
        dao.insertIfAbsent(row);
        AgentConversationElicitationDO reloaded = dao.findByRequestId(TENANT, CONVERSATION, "delivery");
        assertEquals(1, reloaded.getAnswerProtocolVersion());
        assertEquals(4, reloaded.getDispatchAttempt());
        assertEquals(9L, reloaded.getExecutorId());
    }

    @Test
    void durableAnswerRetriesAreBoundedAndReceiptsCannotDowngradeWritten() {
        var r=pending("ack"); r.setAnswerProtocolVersion(1); r.setDispatchAttempt(2); r.setExecutorId(9L);
        dao.insertIfAbsent(r); r.setStatus("ANSWERED"); r.setAnswerId("answer"); r.setAnswerJson("{}");
        r.setDeliveryDeadline(new Date(System.currentTimeMillis()+300000)); r.setNextDeliveryAt(new Date(0));
        assertEquals(1,dao.saveAnswerIfPending(r));
        assertEquals(1,dao.listDueAnswers(new Date(),100).size());
        assertEquals(1,dao.advanceAnswerDelivery(r.getId(),"answer","WRITTEN"));
        assertEquals(0,dao.advanceAnswerDelivery(r.getId(),"answer","RECEIVED"));
        assertEquals("WRITTEN",dao.findByRequestId(TENANT,CONVERSATION,"ack").getDeliveryStatus());
        assertEquals(0,dao.listDueAnswers(new Date(),100).size());
    }

    @Test void cancelClosesSavedDeliveryWithoutChangingWrittenFact() {
        var r=pending("cancel-answer"); r.setAnswerProtocolVersion(1);r.setDispatchAttempt(2);r.setExecutorId(9L);
        dao.insertIfAbsent(r);r.setStatus("ANSWERED");r.setAnswerId("a");r.setDeliveryDeadline(new Date());r.setNextDeliveryAt(new Date(0));dao.saveAnswerIfPending(r);
        assertEquals(1,dao.closeAnswerDeliveriesForTurn(TENANT,CONVERSATION,TURN));
        assertEquals("UNKNOWN",dao.findByRequestId(TENANT,CONVERSATION,"cancel-answer").getDeliveryStatus());
        assertEquals(0,dao.listDueAnswers(new Date(),100).size());
    }

    @ParameterizedTest @ValueSource(strings = {"SAVED", "RECEIVED"})
    void successBeforeAnswerAckKeepsIntentDueAndOriginalExecutorReceiptWins(String stage) {
        var r = savedAnswer("late-ack");
        if ("RECEIVED".equals(stage)) assertEquals(1, dao.advanceAnswerDelivery(r.getId(), "a", stage));
        assertEquals(0, dao.finishAnswerDeliveriesForTurn(TENANT, 9L, CONVERSATION, TURN, 2));
        assertEquals(stage, dao.findByRequestId(TENANT, CONVERSATION, "late-ack").getDeliveryStatus());
        assertEquals(1, dao.listDueAnswers(new Date(), 100).size());
        var convs = mock(AgentConversationDao.class);
        var conv = new AgentConversationDO(); conv.setExecutorId(99L);
        when(convs.findById(TENANT, CONVERSATION)).thenReturn(conv);
        var service = new ConversationAnswerDeliveryService(dao, mock(AgentConversationTurnDao.class),
                convs, mock(ConversationTransport.class));
        service.receive(TENANT, 99L, CONVERSATION, TURN, 2, "late-ack", "a", "WRITTEN");
        service.receive(TENANT + 1, 9L, CONVERSATION, TURN, 2, "late-ack", "a", "WRITTEN");
        service.receive(TENANT, 9L, CONVERSATION + 1, TURN, 2, "late-ack", "a", "WRITTEN");
        service.receive(TENANT, 9L, CONVERSATION, TURN + 1, 2, "late-ack", "a", "WRITTEN");
        service.receive(TENANT, 9L, CONVERSATION, TURN, 3, "late-ack", "a", "WRITTEN");
        service.receive(TENANT, 9L, CONVERSATION, TURN, 2, "other", "a", "WRITTEN");
        service.receive(TENANT, 9L, CONVERSATION, TURN, 2, "late-ack", "other", "WRITTEN");
        assertEquals(stage, dao.findByRequestId(TENANT, CONVERSATION, "late-ack").getDeliveryStatus());
        service.receive(TENANT, 9L, CONVERSATION, TURN, 2, "late-ack", "a", "WRITTEN");
        assertEquals("WRITTEN", dao.findByRequestId(TENANT, CONVERSATION, "late-ack").getDeliveryStatus());
        assertEquals(0, dao.listDueAnswers(new Date(), 100).size());
        verifyNoInteractions(convs); // receipt authority comes from the saved row, not current binding B
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "UNAVAILABLE", "WRITTEN"})
    void terminalDeliveryFactsCannotBeFlippedByLateReceipts(String stage) {
        var r = savedAnswer("final-ack");
        assertEquals(1, dao.advanceAnswerDelivery(r.getId(), "a", stage));
        dao.finishAnswerDeliveriesForTurn(TENANT, 9L, CONVERSATION, TURN, 2);
        assertEquals(0, dao.advanceAnswerDelivery(r.getId(), "a", "RECEIVED"));
        assertEquals(0, dao.advanceAnswerDelivery(r.getId(), "a", "WRITTEN"));
        assertEquals(stage, dao.findByRequestId(TENANT, CONVERSATION, "final-ack").getDeliveryStatus());
        assertEquals(0, dao.listDueAnswers(new Date(), 100).size());
    }

    private AgentConversationElicitationDO savedAnswer(String request) {
        var r = pending(request); r.setAnswerProtocolVersion(1); r.setDispatchAttempt(2); r.setExecutorId(9L);
        dao.insertIfAbsent(r); r.setStatus("ANSWERED"); r.setAnswerId("a"); r.setAnswerJson("{}");
        r.setDeliveryDeadline(new Date(System.currentTimeMillis() + 300000)); r.setNextDeliveryAt(new Date(0));
        assertEquals(1, dao.saveAnswerIfPending(r));
        return r;
    }

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-elicitation-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(AgentConversationElicitationDao.class);
        }
        execScript("conversation-elicitation-schema-h2.sql");
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM agent_conversation_elicitation");
        }
    }

    private static void execScript(String resource) throws Exception {
        String sql;
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        }
    }

    private AgentConversationElicitationDO pending(String requestId) {
        AgentConversationElicitationDO record = new AgentConversationElicitationDO();
        record.setTenantId(TENANT);
        record.setConversationId(CONVERSATION);
        record.setTurnId(TURN);
        record.setRequestId(requestId);
        record.setMode("form");
        record.setMessage("Answer Questions");
        record.setSchemaJson("{\"type\":\"object\"}");
        record.setStatus("PENDING");
        return record;
    }

    private void backdate(String requestId, long millisAgo) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("UPDATE agent_conversation_elicitation SET gmt_create = TIMESTAMP '"
                    + new java.sql.Timestamp(System.currentTimeMillis() - millisAgo)
                    + "' WHERE request_id = '" + requestId + "'");
        }
    }

    /** 分片重投会让同一个 acp_elicitation 事件到达多次，唯一键必须把重复插入吞掉。 */
    @Test
    void insertIfAbsentKeepsSingleRowPerRequestId() {
        assertEquals(1, dao.insertIfAbsent(pending("req-1")));
        assertEquals(0, dao.insertIfAbsent(pending("req-1")));

        List<AgentConversationElicitationDO> rows =
                dao.listPendingByConversation(TENANT, CONVERSATION);
        assertEquals(1, rows.size());
        assertEquals("req-1", rows.get(0).getRequestId());
        assertEquals("form", rows.get(0).getMode());
        assertEquals("Answer Questions", rows.get(0).getMessage());
        assertTrue(rows.get(0).getSchemaJson().contains("\"type\":\"object\""));
        assertNotNull(rows.get(0).getGmtCreate());
    }

    /** 回答、取消、过期三条路径竞争同一次状态转移，只能有一个赢家。 */
    @Test
    void settleIfPendingWinsOnlyOnce() {
        dao.insertIfAbsent(pending("req-1"));

        assertEquals(1, dao.settleIfPending(TENANT, CONVERSATION, "req-1", "ANSWERED",
                "{\"q0\":\"A\"}"));
        assertEquals(0, dao.settleIfPending(TENANT, CONVERSATION, "req-1", "EXPIRED", null));

        AgentConversationElicitationDO settled =
                dao.findByRequestId(TENANT, CONVERSATION, "req-1");
        assertEquals("ANSWERED", settled.getStatus());
        assertEquals("{\"q0\":\"A\"}", settled.getAnswerJson());
    }

    @Test
    void findByRequestIdIsScopedToConversation() {
        dao.insertIfAbsent(pending("req-1"));

        assertNotNull(dao.findByRequestId(TENANT, CONVERSATION, "req-1"));
        // 跨会话猜 requestId 不应命中，否则回答端点的归属校验形同虚设。
        assertNull(dao.findByRequestId(TENANT, CONVERSATION + 1, "req-1"));
        assertNull(dao.findByRequestId(TENANT + 1, CONVERSATION, "req-1"));
    }

    /** 投递失败的补偿：卡片必须重新可答，且上一次的答案不能残留。 */
    @Test
    void restorePendingIfStatusReopensTheCardAndClearsTheAnswer() {
        dao.insertIfAbsent(pending("req-1"));
        dao.settleIfPending(TENANT, CONVERSATION, "req-1", "ANSWERED", "{\"q0\":\"A\"}");

        assertEquals(1, dao.restorePendingIfStatus(TENANT, CONVERSATION, "req-1", "ANSWERED"));

        AgentConversationElicitationDO restored =
                dao.findByRequestId(TENANT, CONVERSATION, "req-1");
        assertEquals("PENDING", restored.getStatus());
        assertNull(restored.getAnswerJson());
        assertEquals(1, dao.listPendingByTurn(TENANT, TURN).size());
    }

    /** 并发赢家（过期 / 取消）已改过状态时，补偿必须原地不动。 */
    @Test
    void restorePendingIfStatusDoesNotOverwriteAConcurrentWinner() {
        dao.insertIfAbsent(pending("req-1"));
        dao.settleIfPending(TENANT, CONVERSATION, "req-1", "EXPIRED", null);

        assertEquals(0, dao.restorePendingIfStatus(TENANT, CONVERSATION, "req-1", "ANSWERED"));
        assertEquals("EXPIRED", dao.findByRequestId(TENANT, CONVERSATION, "req-1").getStatus());
    }

    @Test
    void listPendingByTurnReturnsOnlyPendingRowsOfThatTurn() {
        dao.insertIfAbsent(pending("req-1"));
        dao.insertIfAbsent(pending("req-2"));
        AgentConversationElicitationDO otherTurn = pending("req-3");
        otherTurn.setTurnId(TURN + 1);
        dao.insertIfAbsent(otherTurn);
        dao.settleIfPending(TENANT, CONVERSATION, "req-2", "ANSWERED", "{}");

        List<AgentConversationElicitationDO> rows = dao.listPendingByTurn(TENANT, TURN);

        assertEquals(1, rows.size());
        assertEquals("req-1", rows.get(0).getRequestId());
    }

    /** 过期扫描必须只捞 PENDING 且早于 cutoff 的，否则会把已回答的卡片改成 EXPIRED。 */
    @Test
    void listPendingOlderThanSkipsFreshAndSettledRows() throws Exception {
        dao.insertIfAbsent(pending("stale-pending"));
        dao.insertIfAbsent(pending("stale-answered"));
        dao.insertIfAbsent(pending("fresh-pending"));
        backdate("stale-pending", 40 * 60 * 1000L);
        backdate("stale-answered", 40 * 60 * 1000L);
        dao.settleIfPending(TENANT, CONVERSATION, "stale-answered", "ANSWERED", "{}");

        List<AgentConversationElicitationDO> rows =
                dao.listPendingOlderThan(new Date(System.currentTimeMillis() - 30 * 60 * 1000L), 0L, 50);

        assertEquals(1, rows.size());
        assertEquals("stale-pending", rows.get(0).getRequestId());
        assertEquals(TURN, rows.get(0).getTurnId());
        assertEquals(CONVERSATION, rows.get(0).getConversationId());
    }

    @Test
    void listPendingOlderThanRespectsLimit() throws Exception {
        dao.insertIfAbsent(pending("req-1"));
        dao.insertIfAbsent(pending("req-2"));
        backdate("req-1", 40 * 60 * 1000L);
        backdate("req-2", 40 * 60 * 1000L);

        assertEquals(1, dao.listPendingOlderThan(
                new Date(System.currentTimeMillis() - 30 * 60 * 1000L), 0L, 1).size());
    }
    @Test
    void expiryKeysetAdvancesPastStillPendingRows() throws Exception {
        dao.insertIfAbsent(pending("first"));dao.insertIfAbsent(pending("later"));
        backdate("first",40*60*1000L);backdate("later",40*60*1000L);
        var cutoff=new Date(System.currentTimeMillis()-30*60*1000L);
        var first=dao.listPendingOlderThan(cutoff,0L,1).getFirst();
        var next=dao.listPendingOlderThan(cutoff,first.getId(),1);
        assertEquals("later",next.getFirst().getRequestId());
        assertTrue(dao.listPendingOlderThan(cutoff,next.getFirst().getId(),1).isEmpty());
    }
    @Test void settledLateQuestionRemainsReadableWithoutReopeningOrChangingRealAnswer() {
        var trigger=pending("expired");dao.insertIfAbsent(trigger);
        dao.settleIfPending(TENANT,CONVERSATION,"expired","EXPIRED",null);
        var late=pending("late");late.setStatus("CANCELED");late.setMessage("late original question");dao.insertIfAbsent(late);
        var answered=pending("answered");dao.insertIfAbsent(answered);
        dao.settleIfPending(TENANT,CONVERSATION,"answered","ANSWERED","{\"choice\":1}");
        assertTrue(dao.listPendingByConversation(TENANT,CONVERSATION).isEmpty());
        var history=dao.listQuestionHistory(TENANT,CONVERSATION);
        assertEquals(3,history.size());
        assertTrue(history.stream().anyMatch(q->"late".equals(q.getRequestId()) && "CANCELED".equals(q.getStatus()) && "late original question".equals(q.getMessage())));
        assertTrue(history.stream().anyMatch(q->"answered".equals(q.getRequestId()) && "{\"choice\":1}".equals(q.getAnswerJson())));
    }
}
