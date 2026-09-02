package com.aliyun.autowonder.scheduledtask;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Real MySQL fixture intentionally used by rollout gates.  It does not replace
 * database operations with mocks: callers execute the authoritative SQL files
 * against independent databases in the same MySQL 8 instance.
 */
final class ScheduledTaskIntegrationFixture {
    private final MySQLContainer<?> mysql;

    ScheduledTaskIntegrationFixture(MySQLContainer<?> mysql) {
        this.mysql = mysql;
    }

    Connection open(String database) throws SQLException {
        String jdbcUrl = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/" + database + "?useUnicode=true&characterEncoding=utf-8&useSSL=false";
        return DriverManager.getConnection(jdbcUrl,
                "root", mysql.getPassword());
    }

    void createDatabase(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        }
    }

    void applyFile(Connection connection, String file) throws SQLException, IOException {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ByteArrayResource(Files.readAllBytes(Path.of(file)))));
    }

    void applyClasspath(Connection connection, String resource) throws SQLException {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(resource)));
    }

    void createPreV037LegacyTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE workitem (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, tenant_id BIGINT UNSIGNED NOT NULL, work_type VARCHAR(16) NOT NULL, title VARCHAR(256) NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB");
            // This is the real pre-V037 shape used by the production MyBatis
            // DispatchDao.  V037 must be executable on it, and the migrated
            // database must remain usable by a rolling-upgrade node.
            statement.execute("CREATE TABLE dispatch ("
                    + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT UNSIGNED NOT NULL, "
                    + "workitem_id BIGINT UNSIGNED NOT NULL, "
                    + "sdlc_step_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "agent_id BIGINT UNSIGNED NOT NULL DEFAULT 0, "
                    + "agent_version_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "executor_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "package_oss_ref VARCHAR(512) DEFAULT NULL, "
                    + "status VARCHAR(32) NOT NULL DEFAULT 'PENDING', "
                    + "attempt INT NOT NULL DEFAULT 0, "
                    + "idempotency_key VARCHAR(128) NOT NULL, "
                    + "result_summary MEDIUMTEXT DEFAULT NULL, "
                    + "error VARCHAR(512) DEFAULT NULL, "
                    + "resume_from_dispatch_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "delivery_source_dispatch_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "resume_mode VARCHAR(32) DEFAULT NULL, "
                    + "gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                    + "creator_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "modifier_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "is_deleted TINYINT NOT NULL DEFAULT 0, "
                    + "version INT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(id), "
                    + "UNIQUE KEY uk_idempotency (tenant_id, idempotency_key), "
                    + "KEY idx_workitem (tenant_id, workitem_id), "
                    + "KEY idx_status (tenant_id, status), "
                    + "KEY idx_resume_from (tenant_id, resume_from_dispatch_id), "
                    + "KEY idx_delivery_source (tenant_id, delivery_source_dispatch_id)"
                    + ") ENGINE=InnoDB");
            statement.execute("CREATE TABLE artifact (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, tenant_id BIGINT UNSIGNED NOT NULL, workitem_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_comment (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, tenant_id BIGINT UNSIGNED NOT NULL, workitem_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_comment_mention (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, tenant_id BIGINT UNSIGNED NOT NULL, workitem_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE workitem_comment_delivery (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, tenant_id BIGINT UNSIGNED NOT NULL, workitem_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB");
            statement.execute("INSERT INTO workitem(tenant_id, work_type, title) VALUES (7, 'TASK', 'legacy')");
            statement.execute("INSERT INTO dispatch(tenant_id, workitem_id, idempotency_key) VALUES (7, 42, '42:9:0')");
            statement.execute("INSERT INTO artifact(tenant_id, workitem_id) VALUES (7, 42)");
            statement.execute("INSERT INTO workitem_comment(tenant_id, workitem_id) VALUES (7, 42)");
            statement.execute("INSERT INTO workitem_comment_mention(tenant_id, workitem_id) VALUES (7, 42)");
            statement.execute("INSERT INTO workitem_comment_delivery(tenant_id, workitem_id) VALUES (7, 42)");
        }
    }

    boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return result.next();
        }
    }

    String columnDefault(Connection connection, String table, String column) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            if (!result.next()) {
                throw new SQLException("missing column " + table + "." + column);
            }
            return result.getString("COLUMN_DEF");
        }
    }

    boolean hasIndex(Connection connection, String table, String index, String... columns) throws SQLException {
        try (ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            int seen = 0;
            while (result.next()) {
                if (index.equals(result.getString("INDEX_NAME"))
                        && seen < columns.length
                        && columns[seen].equalsIgnoreCase(result.getString("COLUMN_NAME"))) {
                    seen++;
                    if (seen == columns.length) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
