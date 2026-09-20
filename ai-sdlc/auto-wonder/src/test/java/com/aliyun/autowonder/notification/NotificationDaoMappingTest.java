package com.aliyun.autowonder.notification;

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
 * 断言 mapping/NotificationDao.xml 真实生成的 SQL。Mock 掉 DAO 会掩盖 SQL 语义：
 * 计数条件与列表条件不一致会让分页器总数对不上，删除缺少归属条件会越权删他人通知。
 */
class NotificationDaoMappingTest {

    private static final String NS = "com.aliyun.autowonder.notification.NotificationDao.";
    private static final String COUNT = NS + "countByRecipient";
    private static final String DELETE = NS + "delete";
    private static final String LIST = NS + "listByRecipient";

    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapping/NotificationDao.xml")) {
            assertNotNull(in, "mapping/NotificationDao.xml must be on the classpath");
            new XMLMapperBuilder(in, configuration, "mapping/NotificationDao.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void countWithoutStatusCountsEveryNotificationOfTheRecipient() {
        assertEquals("SELECT COUNT(*) FROM notification WHERE tenant_id = ? AND recipient_id = ?",
                sqlFor(COUNT, countParams(a -> { })));
    }

    @Test
    void countAppliesTheSameStatusPredicateAsTheListQuery() {
        assertEquals("SELECT COUNT(*) FROM notification WHERE tenant_id = ? AND recipient_id = ? AND status = ?",
                sqlFor(COUNT, countParams(a -> a.put("status", "UNREAD"))));
        assertTrue(sqlFor(LIST, listParams(a -> a.put("status", "UNREAD"))).contains("AND status = ?"),
                "列表与计数必须用同一个 status 条件，否则分页器总数与列表内容对不上");
    }

    @Test
    void countOmitsStatusPredicateWhenNotFiltering() {
        assertFalse(sqlFor(COUNT, countParams(a -> { })).contains("status"),
                () -> "全部 Tab 不能带上 status 条件: " + sqlFor(COUNT, countParams(a -> { })));
    }

    @Test
    void countNeverPaginatesSoTotalSpansEveryPage() {
        assertFalse(sqlFor(COUNT, countParams(a -> a.put("status", "READ"))).contains("LIMIT"),
                "计数语句带 LIMIT 会让总数被截断成一页");
    }

    @Test
    void deleteIsScopedToTenantAndRecipientNotOnlyId() {
        assertEquals("DELETE FROM notification WHERE id = ? AND tenant_id = ? AND recipient_id = ?",
                sqlFor(DELETE, deleteParams()));
    }

    @Test
    void deleteBindsOwnershipParametersAfterId() {
        assertEquals(List.of("id", "tenantId", "recipientId"), parameterNames(DELETE, deleteParams()),
                "tenant_id / recipient_id 必须作为绑定参数进入 WHERE，缺一个就能删到他人通知");
    }

    @Test
    void deleteIsPhysicalBecauseNotificationHasNoSoftDeleteColumn() {
        assertTrue(sqlFor(DELETE, deleteParams()).startsWith("DELETE FROM notification"),
                "notification 表无 is_deleted 列，只能物理删除");
    }

    private Map<String, Object> countParams(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> args = new HashMap<>();
        args.put("tenantId", 10002L);
        args.put("recipientId", 10018L);
        customizer.accept(args);
        return args;
    }

    private Map<String, Object> listParams(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> args = countParams(customizer);
        args.put("offset", 0);
        args.put("limit", 10);
        return args;
    }

    private Map<String, Object> deleteParams() {
        Map<String, Object> args = new HashMap<>();
        args.put("id", 77L);
        args.put("tenantId", 10002L);
        args.put("recipientId", 10018L);
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
