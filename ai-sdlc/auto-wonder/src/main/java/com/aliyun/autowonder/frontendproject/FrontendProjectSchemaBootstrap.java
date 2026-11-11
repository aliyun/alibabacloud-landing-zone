package com.aliyun.autowonder.frontendproject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Startup self-heal for the V075 frontend-project probe schema.
 *
 * <p>V075 (docs/migration/V102__frontend_project_probe.sql) is applied by operators, so a
 * database upgraded by hand can still lack {@code frontend_project_probe} /
 * {@code frontend_project_profile} when this code is deployed. Without the tables every probe
 * query fails as an unknown-table SQL error surfaced as a system internal error. This runner is
 * the release-time migration check: it probes information_schema and creates exactly the V075
 * tables when missing, so deployment heals itself.
 *
 * <p>Mirrors {@code WorkitemRecycleBinSchemaBootstrap}: MySQL only, CREATE TABLE IF NOT EXISTS
 * tolerates concurrently booting nodes, any failure only logs a warning so boot never depends on
 * this repair, and the next restart retries.
 */
@Component
public class FrontendProjectSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FrontendProjectSchemaBootstrap.class);

    private static final int PROBE_TIMEOUT_SECONDS = 5;
    private static final int DDL_TIMEOUT_SECONDS = 300;

    static final String CREATE_PROBE_TABLE = """
            CREATE TABLE IF NOT EXISTS `frontend_project_probe` (
              `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
              `repo_id` BIGINT UNSIGNED NOT NULL COMMENT '被探测的仓库',
              `status` VARCHAR(24) NOT NULL COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELED',
              `detector_version` VARCHAR(64) NOT NULL COMMENT '探测器版本，参与画像缓存键',
              `branch` VARCHAR(255) DEFAULT NULL COMMENT '实际探测的分支',
              `commit_sha` VARCHAR(64) DEFAULT NULL COMMENT '实际探测到的提交 SHA；失败时为空',
              `candidate_count` INT DEFAULT NULL COMMENT '识别出的候选前端应用数量',
              `error` VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（已截断）',
              `node_id` VARCHAR(64) DEFAULT NULL COMMENT '执行节点，用于卡死任务补偿',
              `started_at` DATETIME(3) DEFAULT NULL,
              `finished_at` DATETIME(3) DEFAULT NULL,
              `version` INT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
              `creator_id` BIGINT UNSIGNED DEFAULT NULL,
              `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
              `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              KEY `idx_probe_repo` (`workspace_id`, `repo_id`, `id`),
              KEY `idx_probe_status` (`workspace_id`, `status`, `gmt_modified`)
            ) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='前端项目探测任务'
            """;

    static final String CREATE_PROFILE_TABLE = """
            CREATE TABLE IF NOT EXISTS `frontend_project_profile` (
              `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
              `repo_id` BIGINT UNSIGNED NOT NULL COMMENT '所属仓库',
              `probe_id` BIGINT UNSIGNED NOT NULL COMMENT '产生该画像的探测任务',
              `branch` VARCHAR(255) DEFAULT NULL,
              `commit_sha` VARCHAR(64) NOT NULL COMMENT '画像对应的提交',
              `app_root` VARCHAR(512) NOT NULL COMMENT '应用根目录（相对仓库根），仓库根为 .',
              `app_root_hash` CHAR(64) NOT NULL COMMENT 'app_root 的 sha256，用于定长唯一键',
              `package_name` VARCHAR(255) DEFAULT NULL COMMENT 'package.json 的 name',
              `detector_version` VARCHAR(64) NOT NULL COMMENT '探测器版本',
              `profile_json` JSON NOT NULL COMMENT '技术栈与工程约束、事实/推断/未知项',
              `evidence_json` JSON NOT NULL COMMENT '证据：结论 + 来源文件路径',
              `looks_like_library` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '推断为组件库而非独立应用',
              `version` INT NOT NULL DEFAULT 0,
              `creator_id` BIGINT UNSIGNED DEFAULT NULL,
              `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
              `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_profile` (`workspace_id`, `repo_id`, `commit_sha`, `app_root_hash`, `detector_version`),
              KEY `idx_profile_repo` (`workspace_id`, `repo_id`, `id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='前端项目画像快照'
            """;

    private final DataSource dataSource;

    public FrontendProjectSchemaBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            repair(connection);
        } catch (Exception exception) {
            log.warn("frontend-project schema self-heal skipped; run "
                    + "docs/migration/V102__frontend_project_probe.sql manually", exception);
        }
    }

    void repair(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            log.info("frontend-project schema self-heal targets MySQL only, skipped for {}", product);
            return;
        }
        if (!tableExists(connection, "frontend_project_probe")) {
            execute(connection, CREATE_PROBE_TABLE);
            log.info("Created missing table frontend_project_probe (V075 self-heal)");
        }
        if (!tableExists(connection, "frontend_project_profile")) {
            execute(connection, CREATE_PROFILE_TABLE);
            log.info("Created missing table frontend_project_profile (V075 self-heal)");
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            statement.setString(1, tableName);
            statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    private void execute(Connection connection, String ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(DDL_TIMEOUT_SECONDS);
            statement.execute(ddl);
        }
    }
}
