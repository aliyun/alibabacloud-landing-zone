package com.aliyun.autowonder.conversation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「最近一次会话」的语义完全由 SQL 条件与排序决定：多一个 agent_id 条件就退化成
 * 单数字人视角，排序漏掉 last_turn_at 就退回创建时间。XML 文本无法证明这类语义，
 * 沿用仓库 *DaoMappingTest 约定，用 MyBatis 渲染真实 BoundSql 断言。
 */
class AgentConversationDaoMappingTest {

    private static final String NS = "com.aliyun.autowonder.conversation.AgentConversationDao.";

    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapping/AgentConversationDao.xml")) {
            assertNotNull(in, "mapping/AgentConversationDao.xml must be on the classpath");
            new XMLMapperBuilder(in, configuration, "mapping/AgentConversationDao.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void findLatestByBizRefWithoutAgentSpansEveryAgentOfTheWorkitem() {
        Map<String, Object> args = params();

        String sql = sqlFor(NS + "findLatestByBizRefWithoutAgent", args);

        assertFalse(sql.contains("AND agent_id ="), () -> sql);
        assertTrue(sql.contains("channel = ?"), () -> sql);
        assertTrue(sql.contains("biz_ref_type = ?"), () -> sql);
        assertTrue(sql.contains("biz_ref_id = ?"), () -> sql);
        assertEquals(List.of("tenantId", "channel", "bizRefType", "bizRefId"),
                parameterNames(NS + "findLatestByBizRefWithoutAgent", args));
    }

    /** 默认打开的会话必须就是列表首项，两处排序只能是同一口径。 */
    @Test
    void latestQueriesOrderByLastActiveTimeWithCreateTimeFallback() {
        Map<String, Object> args = params();

        for (String statement : List.of("findLatestByBizRefWithoutAgent", "listByBizRef")) {
            String sql = sqlFor(NS + statement, args);
            assertTrue(sql.contains("ORDER BY COALESCE(last_turn_at, gmt_create) DESC, id DESC"),
                    () -> statement + " -> " + sql);
        }
        assertTrue(sqlFor(NS + "findLatestByBizRefWithoutAgent", args).contains("LIMIT 1"));
    }

    private Map<String, Object> params() {
        Map<String, Object> args = new HashMap<>();
        args.put("tenantId", 1L);
        args.put("channel", "WORKITEM_CLARIFICATION");
        args.put("bizRefType", "WORKITEM");
        args.put("bizRefId", 10011L);
        args.put("agentId", 3L);
        return args;
    }

    private String sqlFor(String statementId, Map<String, Object> args) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        return statement.getBoundSql(args).getSql().replaceAll("\\s+", " ").trim();
    }

    private List<String> parameterNames(String statementId, Map<String, Object> args) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(args);
        return boundSql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
    }
}
