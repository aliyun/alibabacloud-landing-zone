package com.aliyun.autowonder.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 MySQL 旧状态验证 V089：存在未处理的重复/孤儿映射时迁移必须阻断且不删数据；
 * 人工处理并移走旧备份后重跑才加唯一键。
 */
@Testcontainers(disabledWithoutDocker = true)
class ExternalWorkitemIdentityMigrationMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("identity_migration").withUsername("test").withPassword("test");

    static DataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createTable("workitem");
        createTable("status_node");
        createTable("workitem_comment");
        createTable("dispatch");
        createTable("external_comment_link");
        createTable("external_workitem_link");
        // 模拟 V089 之前的旧库：还没有租户级唯一身份键。
        jdbc.execute("ALTER TABLE external_workitem_link DROP INDEX uk_external_workitem_identity");

        jdbc.update("INSERT INTO status_node (id, tenant_id, template_id, code, name, category) VALUES "
                + "(3001, 100, 1000, 'doing', '开发中', 'IN_PROGRESS'),"
                + "(3002, 100, 1000, 'new', '新建', 'INIT')");
        jdbc.update("INSERT INTO workitem (id, tenant_id, work_type, title, template_id, status_node_id) VALUES "
                + "(2001, 100, 'REQ', '旧单A', 1000, 3001),"
                + "(2002, 100, 'REQ', '旧单B', 1000, 3002)");
        jdbc.update("INSERT INTO workitem_comment (id, tenant_id, workitem_id, author_type, author_ref, content_md) "
                + "VALUES (4001, 100, 2002, 'HUMAN', 9, '历史评论')");
        jdbc.update("INSERT INTO external_comment_link (tenant_id, provider, binding_id, external_workitem_id, "
                + "external_comment_id, workitem_comment_id, direction) VALUES "
                + "(100, 'AONE', 2, 'EXT-DUP', 'C-1', 4001, 'INBOUND')");
        jdbc.update("INSERT INTO dispatch (tenant_id, workitem_id, agent_id, status, idempotency_key) "
                + "VALUES (100, 2002, 10030, 'RUNNING', 'k-1')");
        jdbc.update("INSERT INTO external_workitem_link (tenant_id, provider, binding_id, external_project_id, "
                + "external_workitem_id, workitem_id) VALUES "
                + "(100, 'AONE', 1, 'P-1', 'EXT-DUP', 2001),"
                + "(100, 'AONE', 2, 'P-2', 'EXT-DUP', 2002)");
    }

    static void createTable(String name) throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String marker = "CREATE TABLE IF NOT EXISTS `" + name + "` (";
        int start = schema.indexOf(marker);
        assertTrue(start >= 0, "missing table " + name);
        int end = schema.indexOf(';', start) + 1;
        jdbc.execute(schema.substring(start, end));
    }

    @Test
    void migrationBlocksOnUnhandledDuplicatesAndSucceedsAfterResolution() throws Exception {
        RuntimeException blocked = assertThrows(RuntimeException.class, this::runMigration);
        assertTrue(String.valueOf(blocked.getMessage()).contains("v089_blocked_by_duplicate_links"),
                "migration must block through the duplicate guard: " + blocked.getMessage());

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_workitem_link WHERE external_workitem_id = 'EXT-DUP'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_workitem_identity_conflicts_v089", Integer.class));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT comment_count, dispatch_count, active_dispatch_count, external_comment_link_count "
                        + "FROM external_workitem_identity_conflicts_v089 WHERE workitem_id = 2002");
        assertEquals(1L, ((Number) row.get("comment_count")).longValue());
        assertEquals(1L, ((Number) row.get("dispatch_count")).longValue());
        assertEquals(1L, ((Number) row.get("active_dispatch_count")).longValue());
        assertEquals(1L, ((Number) row.get("external_comment_link_count")).longValue());
        assertEquals(0, uniqueKeyCount());

        RuntimeException staleBackup = assertThrows(RuntimeException.class, this::runMigration);
        assertTrue(String.valueOf(staleBackup.getMessage()).contains("already exists"),
                "rerun must not silently reuse the previous backup: " + staleBackup.getMessage());

        jdbc.execute("DROP TABLE external_workitem_link_backup_v089");
        jdbc.update("DELETE FROM external_workitem_link WHERE workitem_id = 2002");
        runMigration();

        assertEquals(1, uniqueKeyCount());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_workitem_identity_conflicts_v089", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO external_workitem_link (tenant_id, provider, binding_id, external_project_id, "
                        + "external_workitem_id, workitem_id) VALUES (100, 'AONE', 3, 'P-3', 'EXT-DUP', 2002)"));
    }

    private void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/migration/V089__external_workitem_identity.sql"));
        }
    }

    private int uniqueKeyCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'external_workitem_link' "
                        + "AND index_name = 'uk_external_workitem_identity'", Integer.class);
        return count == null ? 0 : count;
    }
}
