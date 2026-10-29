package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * credits 聚合真实执行 H2(MySQL 模式)。按日趋势查询依赖 MySQL 的 DATE() 函数，
 * 无法在 H2 上执行，其语句结构由 InsightsDaoMappingTest 断言。
 */
class InsightsCreditsDaoTest {

    private static final Timestamp SINCE = Timestamp.valueOf("2026-07-01 00:00:00");

    @Test
    void sumsCreditsWithTenantTimeAndAgentFencesAcrossDispatchAndConversations() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);
        createConversationUsageTable(jdbc);
        jdbc.execute("INSERT INTO dispatch_ai_usage VALUES"
                + "(1,9,10,100,100,1.5000,1000,'2026-07-02 08:00:00'),"
                + "(2,9,10,101,100,2.5000,2000,'2026-07-02 09:00:00'),"
                + "(3,9,11,102,100,NULL,3000,'2026-07-02 10:00:00'),"
                + "(4,9,11,103,101,10.0000,4000,'2026-07-02 11:00:00'),"
                + "(5,99,12,104,100,100.0000,5000,'2026-07-02 12:00:00'),"
                + "(6,9,12,105,100,5.0000,6000,'2026-06-01 12:00:00')");
        insertConversationUsage(jdbc, 9, 10L, 700L, 100L, "WORKITEM_CLARIFICATION", "0.5000", "2026-07-02 13:00:00");
        insertConversationUsage(jdbc, 9, null, 701L, 100L, "PLATFORM_ASSISTANT", "1.2500", "2026-07-02 14:00:00");
        insertConversationUsage(jdbc, 9, 12L, 702L, 101L, "DINGTALK", "3.0000", "2026-07-02 15:00:00");
        insertConversationUsage(jdbc, 99, null, 703L, 100L, "PLATFORM_ASSISTANT", "50.0000", "2026-07-02 16:00:00");
        insertConversationUsage(jdbc, 9, 12L, 704L, 100L, "FEISHU", "7.0000", "2026-06-01 13:00:00");

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            assertEquals(0, new BigDecimal("5.7500").compareTo(dao.countTotalCredits(9L, SINCE, 100L)));
            assertEquals(0, new BigDecimal("18.7500").compareTo(dao.countTotalCredits(9L, SINCE, null)));
        }
    }

    @Test
    void returnsZeroWhenTablesAreEmptyOrAllCreditsAreNull() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);
        createConversationUsageTable(jdbc);

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            assertEquals(0, BigDecimal.ZERO.compareTo(dao.countTotalCredits(9L, SINCE, null)));

            jdbc.execute("INSERT INTO dispatch_ai_usage VALUES(1,9,10,100,100,NULL,1000,'2026-07-02 08:00:00')");
            insertConversationUsage(jdbc, 9, null, 700L, 100L, "PLATFORM_ASSISTANT", null, "2026-07-02 09:00:00");
            assertEquals(0, BigDecimal.ZERO.compareTo(dao.countTotalCredits(9L, SINCE, null)));
            assertEquals(0, BigDecimal.ZERO.compareTo(dao.countTotalCredits(9L, SINCE, 100L)));
        }
    }

    @Test
    void countsCreditWorkitemsAcrossDispatchAndWorkitemBoundConversations() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);
        createConversationUsageTable(jdbc);
        jdbc.execute("INSERT INTO dispatch_ai_usage VALUES"
                + "(1,9,10,100,100,1.0000,10,'2026-07-02 08:00:00'),"
                + "(2,9,11,101,101,1.0000,10,'2026-07-02 09:00:00'),"
                + "(3,99,12,102,100,1.0000,10,'2026-07-02 10:00:00'),"
                + "(4,9,13,103,100,1.0000,10,'2026-06-01 10:00:00')");
        insertConversationUsage(jdbc, 9, 11L, 700L, 100L, "WORKITEM_CLARIFICATION", "1", "2026-07-02 11:00:00");
        insertConversationUsage(jdbc, 9, 12L, 701L, 100L, "WORKITEM_CLARIFICATION", "1", "2026-07-02 12:00:00");
        insertConversationUsage(jdbc, 9, null, 702L, 100L, "PLATFORM_ASSISTANT", "1", "2026-07-02 13:00:00");
        insertConversationUsage(jdbc, 9, 14L, 703L, 100L, "WORKITEM_CLARIFICATION", "1", "2026-07-02 14:00:00");
        insertConversationUsage(jdbc, 9, 15L, 704L, 100L, "WORKITEM_CLARIFICATION", "1", "2026-06-01 14:00:00");
        insertConversationUsage(jdbc, 99, 12L, 705L, 100L, "WORKITEM_CLARIFICATION", "1", "2026-07-02 15:00:00");

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            // Token 分母保持派发口径。
            assertEquals(2, dao.countUsageWorkitems(9L, SINCE, null));
            assertEquals(1, dao.countUsageWorkitems(9L, SINCE, 100L));
            // Credits 分母 = 派发工单 {10,11} ∪ 工单绑定对话 {11,12,14}。
            assertEquals(4, dao.countCreditUsageWorkitems(9L, SINCE, null));
            assertEquals(4, dao.countCreditUsageWorkitems(9L, SINCE, 100L));
            assertEquals(1, dao.countCreditUsageWorkitems(9L, SINCE, 101L));
        }
    }

    @Test
    void summarizesWorkerCreditsWithConversationShare() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);
        createConversationUsageTable(jdbc);
        createAgentTable(jdbc);
        jdbc.execute("INSERT INTO agent VALUES(100,9,'DEV',0),(101,9,'CR',0),(102,9,'DELETED',1)");
        jdbc.execute("INSERT INTO dispatch_ai_usage VALUES"
                + "(1,9,10,100,100,4.0000,10,'2026-07-02 08:00:00'),"
                + "(2,9,11,101,101,10.0000,10,'2026-07-02 09:00:00'),"
                + "(3,9,12,102,999,2.0000,10,'2026-07-02 10:00:00'),"
                + "(4,9,13,103,102,8.0000,10,'2026-07-02 11:00:00'),"
                + "(5,99,14,104,100,50.0000,10,'2026-07-02 12:00:00'),"
                + "(6,9,15,105,100,9.0000,10,'2026-06-01 12:00:00')");
        insertConversationUsage(jdbc, 9, null, 700L, 100L, "PLATFORM_ASSISTANT", "1.5000", "2026-07-02 13:00:00");
        insertConversationUsage(jdbc, 9, 10L, 701L, 100L, "WORKITEM_CLARIFICATION", "0.2500", "2026-07-02 14:00:00");
        insertConversationUsage(jdbc, 9, 12L, 702L, 101L, "DINGTALK", "3.0000", "2026-07-02 15:00:00");
        insertConversationUsage(jdbc, 9, null, 703L, null, "PLATFORM_ASSISTANT", "5.0000", "2026-07-02 16:00:00");
        insertConversationUsage(jdbc, 9, null, 704L, 100L, "PLATFORM_ASSISTANT", "7.0000", "2026-06-01 13:00:00");

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            List<InsightMetricsVO.WorkerCost> rows = dao.listWorkerCredits(9L, SINCE, null);

            assertEquals(List.of(100L, 101L, 102L, 999L),
                    rows.stream().map(InsightMetricsVO.WorkerCost::getAgentId).toList());
            assertEquals(Arrays.asList("DEV", "CR", null, null),
                    rows.stream().map(InsightMetricsVO.WorkerCost::getAgentName).toList());
            assertCredits("5.7500", rows.get(0).getCredits());
            assertCredits("1.7500", rows.get(0).getConversationCredits());
            assertCredits("13.0000", rows.get(1).getCredits());
            assertCredits("3.0000", rows.get(1).getConversationCredits());
            assertCredits("8.0000", rows.get(2).getCredits());
            assertCredits("0", rows.get(2).getConversationCredits());
            assertCredits("2.0000", rows.get(3).getCredits());

            List<InsightMetricsVO.WorkerCost> filtered = dao.listWorkerCredits(9L, SINCE, 101L);
            assertEquals(1, filtered.size());
            assertCredits("13.0000", filtered.get(0).getCredits());
            assertCredits("3.0000", filtered.get(0).getConversationCredits());
        }
    }

    @Test
    void groupsCreditsByFixedSourceAndKeepsUnknownChannelsUnderOther() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);
        createConversationUsageTable(jdbc);
        jdbc.execute("INSERT INTO dispatch_ai_usage VALUES(1,9,10,100,100,4.0000,10,'2026-07-02 08:00:00')");
        insertConversationUsage(jdbc, 9, 10L, 700L, 100L, "WORKITEM_CLARIFICATION", "1.0000", "2026-07-02 09:00:00");
        insertConversationUsage(jdbc, 9, null, 701L, 100L, "PLATFORM_ASSISTANT", "2.0000", "2026-07-02 10:00:00");
        insertConversationUsage(jdbc, 9, null, 702L, 100L, "DINGTALK", "3.0000", "2026-07-02 11:00:00");
        insertConversationUsage(jdbc, 9, null, 703L, 101L, "FEISHU", "4.0000", "2026-07-02 12:00:00");
        insertConversationUsage(jdbc, 9, null, 704L, 101L, "PLATFORM_INTERNAL", "5.0000", "2026-07-02 13:00:00");
        insertConversationUsage(jdbc, 9, null, 705L, 101L, "BRAND_NEW_CHANNEL", "6.0000", "2026-07-02 14:00:00");
        insertConversationUsage(jdbc, 9, null, 706L, 101L, null, "0.5000", "2026-07-02 15:00:00");
        insertConversationUsage(jdbc, 9, null, 707L, 101L, "DINGTALK", null, "2026-07-02 16:00:00");
        insertConversationUsage(jdbc, 9, null, 708L, 101L, "DINGTALK", "9.0000", "2026-06-01 12:00:00");
        insertConversationUsage(jdbc, 99, null, 709L, 100L, "DINGTALK", "50.0000", "2026-07-02 17:00:00");

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            Map<String, BigDecimal> sources = bySource(dao.listCreditsBySource(9L, SINCE, null));

            assertEquals(5, sources.size());
            assertCredits("4.0000", sources.get("DISPATCH"));
            assertCredits("1.0000", sources.get("WORKITEM_CLARIFICATION"));
            assertCredits("2.0000", sources.get("PLATFORM_ASSISTANT"));
            assertCredits("7.0000", sources.get("IM"));
            assertCredits("11.5000", sources.get("OTHER_CONVERSATION"));
            BigDecimal classifiedSum = sources.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, dao.countTotalCredits(9L, SINCE, null).compareTo(classifiedSum));

            Map<String, BigDecimal> agentSources = bySource(dao.listCreditsBySource(9L, SINCE, 101L));
            assertEquals(2, agentSources.size());
            assertCredits("4.0000", agentSources.get("IM"));
            assertCredits("11.5000", agentSources.get("OTHER_CONVERSATION"));
        }
    }

    @Test
    void listsActiveWorkersIncludingConversationOnlyAgents() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createConversationUsageTable(jdbc);
        createAgentTable(jdbc);
        jdbc.execute("CREATE TABLE dispatch(id BIGINT, tenant_id BIGINT, agent_id BIGINT, is_deleted INT)");
        jdbc.execute("INSERT INTO agent VALUES(100,9,'DEV',0),(101,9,'CR',0),(102,9,'COS',0),"
                + "(103,9,'OLD',1),(104,99,'OTHER',0)");
        jdbc.execute("INSERT INTO dispatch VALUES(1,9,100,0),(2,9,103,0),(3,99,104,0)");
        insertConversationUsage(jdbc, 9, null, 700L, 102L, "PLATFORM_ASSISTANT", "1", "2026-07-02 09:00:00");
        insertConversationUsage(jdbc, 9, null, 701L, 101L, "DINGTALK", "1", "2026-07-02 10:00:00");
        insertConversationUsage(jdbc, 99, null, 702L, 104L, "PLATFORM_ASSISTANT", "1", "2026-07-02 11:00:00");

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            List<InsightWorkerVO> workers = dao.listActiveWorkers(9L);

            assertEquals(List.of("COS", "CR", "DEV"),
                    workers.stream().map(InsightWorkerVO::getName).toList());
            assertEquals(List.of("102", "101", "100"),
                    workers.stream().map(InsightWorkerVO::getId).toList());
        }
    }

    private Map<String, BigDecimal> bySource(List<InsightMetricsVO.CreditSource> rows) {
        Map<String, BigDecimal> sources = new HashMap<>();
        for (InsightMetricsVO.CreditSource row : rows) {
            sources.put(row.getSource(), row.getCredits());
        }
        return sources;
    }

    private void assertCredits(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private void createUsageTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE dispatch_ai_usage(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,"
                + "dispatch_id BIGINT,agent_id BIGINT,credits DECIMAL(18,4),total_tokens BIGINT,usage_at TIMESTAMP)");
    }

    private void createConversationUsageTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE agent_conversation_usage(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,"
                + "conversation_id BIGINT,turn_id BIGINT,agent_id BIGINT,executor_id BIGINT,"
                + "channel VARCHAR(64),provider VARCHAR(64),model VARCHAR(128),"
                + "input_tokens BIGINT,output_tokens BIGINT,cache_read_tokens BIGINT,"
                + "cache_write_tokens BIGINT,reasoning_tokens BIGINT,credits DECIMAL(12,4),usage_at TIMESTAMP)");
    }

    private void createAgentTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE agent(id BIGINT, tenant_id BIGINT, name VARCHAR(128), is_deleted INT)");
    }

    private void insertConversationUsage(JdbcTemplate jdbc, long tenantId, Long workitemId,
                                         long conversationId, Long agentId, String channel,
                                         String credits, String usageAt) {
        jdbc.update("INSERT INTO agent_conversation_usage"
                        + "(tenant_id,workitem_id,conversation_id,turn_id,agent_id,channel,credits,usage_at)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                tenantId, workitemId, conversationId, 1L, agentId, channel,
                credits == null ? null : new BigDecimal(credits), Timestamp.valueOf(usageAt));
    }

    private SqlSession openSession(DriverManagerDataSource ds) throws Exception {
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        // InsightsDao.xml 跨命名空间 include AuditLogDao.riskLevelCase，被依赖的 mapper 必须先解析。
        for (String name : List.of("AuditLogDao", "InsightsDao")) {
            try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/" + name + ".xml"))) {
                new XMLMapperBuilder(stream, config, name, config.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(config).openSession();
    }
}
