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
 * 删除认领语义依赖 SQL 条件本身：认领行不可再绑定、只有认领行可被删除、
 * 孤儿扫描同时覆盖认领重试与超期未绑定。XML 文本无法证明条件实际生效，
 * 这里沿用仓库 *DaoMappingTest 约定，用 MyBatis 渲染真实 BoundSql 断言。
 */
class ClarificationAttachmentDaoMappingTest {

    private static final String NS = "com.aliyun.autowonder.conversation.ClarificationAttachmentDao.";

    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapping/ClarificationAttachmentDao.xml")) {
            assertNotNull(in, "mapping/ClarificationAttachmentDao.xml must be on the classpath");
            new XMLMapperBuilder(in, configuration, "mapping/ClarificationAttachmentDao.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void bindTurnRefusesRowsClaimedForDeletion() {
        Map<String, Object> args = params();
        args.put("ids", List.of(5L, 6L));

        String sql = sqlFor(NS + "bindTurn", args);

        assertTrue(sql.contains("turn_id IS NULL"), () -> sql);
        assertTrue(sql.contains("deleting_at IS NULL"), () -> sql);
        assertTrue(parameterNames(NS + "bindTurn", args).containsAll(
                List.of("tenantId", "conversationId", "uploaderUserId", "turnId")));
    }

    @Test
    void markDeletingClaimsOnlyOwnUnboundUnclaimedRow() {
        Map<String, Object> args = params();
        args.put("id", 5L);

        String sql = sqlFor(NS + "markDeleting", args);

        assertTrue(sql.startsWith("UPDATE clarification_attachment SET deleting_at = CURRENT_TIMESTAMP(3)"),
                () -> sql);
        assertTrue(sql.contains("uploader_user_id = ?"), () -> sql);
        assertTrue(sql.contains("turn_id IS NULL"), () -> sql);
        assertTrue(sql.contains("deleting_at IS NULL"), () -> sql);
        assertEquals(List.of("tenantId", "conversationId", "uploaderUserId", "id"),
                parameterNames(NS + "markDeleting", args));
    }

    @Test
    void markOrphanDeletingClaimsOnlyUnboundUnclaimedRow() {
        Map<String, Object> args = params();
        args.put("id", 5L);

        String sql = sqlFor(NS + "markOrphanDeleting", args);

        assertTrue(sql.contains("turn_id IS NULL"), () -> sql);
        assertTrue(sql.contains("deleting_at IS NULL"), () -> sql);
        assertEquals(List.of("tenantId", "id"),
                parameterNames(NS + "markOrphanDeleting", args));
    }

    @Test
    void deleteClaimedDeletesOnlyClaimedRows() {
        Map<String, Object> args = params();
        args.put("id", 5L);

        String sql = sqlFor(NS + "deleteClaimed", args);

        assertTrue(sql.startsWith("DELETE FROM clarification_attachment"), () -> sql);
        assertTrue(sql.contains("deleting_at IS NOT NULL"), () -> sql);
        assertEquals(List.of("tenantId", "conversationId", "id"),
                parameterNames(NS + "deleteClaimed", args));
    }

    @Test
    void listOrphansCoversClaimedRetriesAndExpiredUnboundRows() {
        Map<String, Object> args = new HashMap<>();
        args.put("cutoff", new java.util.Date());
        args.put("limit", 200);

        String sql = sqlFor(NS + "listOrphans", args);

        assertTrue(sql.contains("deleting_at IS NOT NULL"), () -> sql);
        assertTrue(sql.contains("turn_id IS NULL AND gmt_create < ?"), () -> sql);
        assertEquals(List.of("cutoff", "limit"), parameterNames(NS + "listOrphans", args));
    }

    @Test
    void workitemCleanupStillDeletesRowsRegardlessOfBindingOrClaim() {
        Map<String, Object> args = params();
        args.put("id", 5L);

        String sql = sqlFor(NS + "deleteById", args);

        assertFalse(sql.contains("turn_id"), () -> sql);
        assertFalse(sql.contains("deleting_at"), () -> sql);
    }

    private Map<String, Object> params() {
        Map<String, Object> args = new HashMap<>();
        args.put("tenantId", 1L);
        args.put("conversationId", 77L);
        args.put("uploaderUserId", 42L);
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
