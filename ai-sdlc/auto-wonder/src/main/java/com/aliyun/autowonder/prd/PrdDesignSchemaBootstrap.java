package com.aliyun.autowonder.prd;

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
 * Startup self-heal for the V076 PRD design schema.
 *
 * <p>V076 (docs/migration/V104__prd_design_version.sql) is applied by operators, so a database
 * upgraded by hand can still lack the PRD tables when this code is deployed. Mirrors
 * {@code WorkitemRecycleBinSchemaBootstrap} and {@code FrontendProjectSchemaBootstrap}: MySQL only,
 * CREATE TABLE IF NOT EXISTS tolerates concurrently booting nodes, any failure only logs a warning
 * so boot never depends on this repair, and the next restart retries.
 */
@Component
public class PrdDesignSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrdDesignSchemaBootstrap.class);

    private static final int PROBE_TIMEOUT_SECONDS = 5;
    private static final int DDL_TIMEOUT_SECONDS = 300;

    static final String CREATE_DESIGN_TABLE = """
            CREATE TABLE IF NOT EXISTS `prd_design` (
              `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
              `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '承载需求的工单',
              `target_repo_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标前端应用所属仓库；概念设计阶段可为空',
              `target_profile_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标前端应用画像快照',
              `target_app_root` VARCHAR(512) DEFAULT NULL COMMENT '目标应用根目录',
              `target_commit_sha` VARCHAR(64) DEFAULT NULL COMMENT '目标应用探测提交',
              `target_config_json` MEDIUMTEXT DEFAULT NULL COMMENT '用户手工配置的目标项目快照',
              `confirmed_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '已确认版本；NULL 表示尚未确认',
              `delivery_mode` VARCHAR(24) NOT NULL DEFAULT 'LEGACY' COMMENT 'LEGACY/PRD_DRIVEN',
              `latest_version_no` INT NOT NULL DEFAULT 0 COMMENT '已分配的最大版本号',
              `content_language` VARCHAR(16) DEFAULT NULL COMMENT '生成内容语言',
              `version` INT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
              `creator_id` BIGINT UNSIGNED DEFAULT NULL,
              `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
              `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_prd_design_workitem` (`workspace_id`, `workitem_id`),
              KEY `idx_prd_design_repo` (`workspace_id`, `target_repo_id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单 PRD 设计聚合根'
            """;

    static final String CREATE_VERSION_TABLE = """
            CREATE TABLE IF NOT EXISTS `prd_design_version` (
              `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
              `design_id` BIGINT UNSIGNED NOT NULL COMMENT '所属设计',
              `version_no` INT NOT NULL COMMENT '版本序号，从 1 起递增',
              `status` VARCHAR(24) NOT NULL COMMENT 'DRAFT/PENDING_REVIEW/CONFIRMED',
              `source_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '派生自哪个版本',
              `design_json` LONGTEXT NOT NULL COMMENT '版本化结构化设计数据（唯一真相来源）',
              `design_sha256` CHAR(64) NOT NULL COMMENT 'design_json 的 sha256',
              `html_oss_ref` VARCHAR(512) DEFAULT NULL COMMENT '渲染出的 HTML PRD 对象存储引用',
              `html_sha256` CHAR(64) DEFAULT NULL COMMENT 'HTML 内容的 sha256',
              `html_size` BIGINT DEFAULT NULL COMMENT 'HTML 字节数',
              `renderer_version` VARCHAR(64) DEFAULT NULL COMMENT 'HTML 渲染器版本',
              `summary` VARCHAR(1024) DEFAULT NULL COMMENT '内容摘要',
              `change_summary` VARCHAR(2048) DEFAULT NULL COMMENT '相对来源版本的变化摘要',
              `requirement_count` INT DEFAULT NULL,
              `open_question_count` INT DEFAULT NULL,
              `blocking_question_count` INT DEFAULT NULL,
              `ai_session_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '产生该版本的生成任务',
              `confirmed_by` BIGINT UNSIGNED DEFAULT NULL,
              `confirmed_at` DATETIME(3) DEFAULT NULL,
              `confirmed_sha256` CHAR(64) DEFAULT NULL COMMENT '确认时绑定的内容摘要',
              `is_deleted` TINYINT NOT NULL DEFAULT 0,
              `deleted_at` DATETIME(3) DEFAULT NULL,
              `version` INT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
              `creator_id` BIGINT UNSIGNED DEFAULT NULL,
              `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
              `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_prd_version` (`workspace_id`, `design_id`, `version_no`),
              KEY `idx_prd_version_design` (`workspace_id`, `design_id`, `status`),
              KEY `idx_prd_version_session` (`workspace_id`, `ai_session_id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='PRD 设计版本'
            """;

    static final String CREATE_ACCEPTANCE_TABLE = """
            CREATE TABLE IF NOT EXISTS `prd_requirement_acceptance` (
              `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
              `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工单',
              `dispatch_id` BIGINT UNSIGNED NOT NULL COMMENT '产生该结果的交付',
              `design_version_id` BIGINT UNSIGNED NOT NULL COMMENT '冻结的设计版本',
              `requirement_key` VARCHAR(64) NOT NULL COMMENT '需求稳定编号',
              `implementation` VARCHAR(24) NOT NULL COMMENT 'IMPLEMENTED/PARTIAL/NOT_IMPLEMENTED',
              `verdict` VARCHAR(24) NOT NULL COMMENT 'PASSED/FAILED/NOT_VERIFIED/NOT_APPLICABLE',
              `note` VARCHAR(2048) DEFAULT NULL,
              `not_applicable_reason` VARCHAR(1024) DEFAULT NULL,
              `evidence_json` JSON DEFAULT NULL COMMENT '证据引用：代码/MR/截图/测试/预览链接',
              `reported_at` DATETIME(3) DEFAULT NULL,
              `version` INT NOT NULL DEFAULT 0,
              `creator_id` BIGINT UNSIGNED DEFAULT NULL,
              `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
              `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_prd_acceptance` (`workspace_id`, `dispatch_id`, `requirement_key`),
              KEY `idx_prd_acceptance_workitem` (`workspace_id`, `workitem_id`, `id`),
              KEY `idx_prd_acceptance_version` (`workspace_id`, `design_version_id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='PRD 需求验收结果'
            """;

    static final String CREATE_BINDING_TABLE = """
            CREATE TABLE IF NOT EXISTS `prd_delivery_binding` (
              `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
              `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '关联工单',
              `dispatch_id` BIGINT UNSIGNED NOT NULL COMMENT '关联交付',
              `design_id` BIGINT UNSIGNED NOT NULL COMMENT '设计聚合根',
              `design_version_id` BIGINT UNSIGNED NOT NULL COMMENT '钉住的设计版本',
              `version_no` INT NOT NULL COMMENT '钉住的版本序号',
              `design_sha256` CHAR(64) NOT NULL COMMENT '钉住的内容摘要',
              `html_sha256` CHAR(64) DEFAULT NULL COMMENT '钉住的 HTML 摘要',
              `target_repo_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标仓库',
              `target_profile_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标应用画像',
              `target_app_root` VARCHAR(512) DEFAULT NULL COMMENT '目标应用根目录',
              `target_commit_sha` VARCHAR(64) DEFAULT NULL COMMENT '探测提交',
              `version` INT NOT NULL DEFAULT 0,
              `creator_id` BIGINT UNSIGNED DEFAULT NULL,
              `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
              `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_prd_binding_dispatch` (`workspace_id`, `dispatch_id`),
              KEY `idx_prd_binding_workitem` (`workspace_id`, `workitem_id`, `id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='PRD 交付冻结绑定'
            """;

    private final DataSource dataSource;

    public PrdDesignSchemaBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            repair(connection);
        } catch (Exception exception) {
            log.warn("prd-design schema self-heal skipped; run "
                    + "docs/migration/V104__prd_design_version.sql and V107__prd_manual_target.sql manually", exception);
        }
    }

    void repair(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            log.info("prd-design schema self-heal targets MySQL only, skipped for {}", product);
            return;
        }
        if (!tableExists(connection, "prd_design")) {
            execute(connection, CREATE_DESIGN_TABLE);
            log.info("Created missing table prd_design (V076 self-heal)");
        }
        if (!tableExists(connection, "prd_design_version")) {
            execute(connection, CREATE_VERSION_TABLE);
            log.info("Created missing table prd_design_version (V076 self-heal)");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'prd_design' AND COLUMN_NAME = 'target_config_json'")) {
            statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) == 0) {
                    execute(connection, "ALTER TABLE prd_design ADD COLUMN target_config_json MEDIUMTEXT DEFAULT NULL");
                }
            }
        }
        if (!tableExists(connection, "prd_requirement_acceptance")) {
            execute(connection, CREATE_ACCEPTANCE_TABLE);
            log.info("Created missing table prd_requirement_acceptance (V076 self-heal)");
        }
        if (!tableExists(connection, "prd_delivery_binding")) {
            execute(connection, CREATE_BINDING_TABLE);
            log.info("Created missing table prd_delivery_binding (V076 self-heal)");
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
