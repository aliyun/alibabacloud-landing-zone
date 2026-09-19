package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V051 建表语句的契约测试。migration 与 autowonder-schema.sql 必须一致，
 * 否则新环境初始化出来的表结构和存量环境迁移出来的不一样，Owner 边界会在某一类环境上直接失效。
 */
class PlatformConversationSchemaContractTest {

    private static final String MIGRATION = "docs/migration/V062__platform_chief_conversation.sql";
    private static final String SCHEMA = "docs/autowonder-schema.sql";
    private static final List<String> NEW_TABLES = List.of(
            "conversation_share",
            "conversation_turn_artifact",
            "conversation_action_plan",
            "conversation_action_step");

    private String read(String relative) throws Exception {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    private String tableDefinition(String sql, String table) {
        Pattern pattern = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + Pattern.quote(table) + "`\\s*\\(.*?\\)"
                        + "\\s*ENGINE=InnoDB.*?;");
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), "schema must declare " + table);
        return matcher.group();
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    /** 新列必须全部可空，否则存量 DINGTALK / WORKITEM_CLARIFICATION 行会在迁移时被写坏。 */
    @Test
    void migrationAddsNullablePlatformColumnsToAgentConversation() throws Exception {
        String migration = normalize(read(MIGRATION));
        assertTrue(migration.contains("ALTER TABLE `agent_conversation`"),
                "平台字段通过 ALTER 增量加到既有表，不新建影子表");
        assertTrue(migration.contains(
                        "`owner_user_id` BIGINT NULL COMMENT 'PLATFORM_ASSISTANT immutable owner' AFTER `tenant_id`"),
                "Owner 一旦写入不可变更，可空是为了兼容遗留渠道");
        assertTrue(migration.contains("`title` VARCHAR(255) NULL AFTER `channel_conversation_id`"),
                "标题可空，自动标题失败不能阻塞建会话");
        assertTrue(migration.contains("`title_source` VARCHAR(16) NULL COMMENT 'AUTO/USER' AFTER `title`"),
                "必须区分自动标题与用户手改，否则重命名会被下一轮自动标题覆盖");
        assertTrue(migration.contains("`archived_at` DATETIME NULL AFTER `last_turn_at`"), "归档用时间戳而非布尔，便于按时间清理");
        assertTrue(migration.contains("`deleted_at` DATETIME NULL AFTER `archived_at`"), "删除必须是软删，服务端仍是历史的唯一真相源");
        assertTrue(migration.contains(
                        "ADD KEY `idx_platform_owner_list` (`tenant_id`, `channel`, `owner_user_id`, `deleted_at`, `last_turn_at`, `id`)"),
                "侧边栏列表按 Owner + 软删 + 最近一轮排序，缺索引会全表扫");
    }

    /** schema 里的列顺序必须与 migration 的 AFTER 顺序一致，方便逐行比对。 */
    @Test
    void schemaFileKeepsAgentConversationColumnsInMigrationOrder() throws Exception {
        String definition = normalize(tableDefinition(read(SCHEMA), "agent_conversation"));
        List<String> columns = List.of("`tenant_id`", "`owner_user_id`", "`agent_id`", "`channel`",
                "`channel_conversation_id`", "`title`", "`title_source`", "`cli_session_ref`",
                "`last_turn_at`", "`archived_at`", "`deleted_at`", "`gmt_create`");
        int previous = -1;
        for (String column : columns) {
            int index = definition.indexOf(column);
            assertTrue(index > previous, column + " 必须按 V051 的 AFTER 顺序出现在 agent_conversation 中");
            previous = index;
        }
        assertTrue(definition.contains("KEY `idx_platform_owner_list`"), "索引也要同步进全量 schema");
        assertTrue(definition.contains("'DINGTALK / WORKITEM_CLARIFICATION / PLATFORM_ASSISTANT etc.'"),
                "channel 注释要声明新渠道，避免后来者以为只有钉钉与工单澄清");
    }

    @Test
    void migrationCreatesShareTableWithSingleRowPerGrantee() throws Exception {
        String definition = normalize(tableDefinition(read(MIGRATION), "conversation_share"));
        assertTrue(definition.contains("`permission` VARCHAR(16) NOT NULL DEFAULT 'READ'"),
                "本期只有只读分享，被分享人不能续聊");
        assertTrue(definition.contains("`created_by` BIGINT NOT NULL"), "撤销权限要能校验只有 Owner 能撤");
        assertTrue(definition.contains(
                        "UNIQUE KEY `uk_conversation_grantee` (`tenant_id`, `conversation_id`, `grantee_user_id`)"),
                "重复分享必须落在同一行，否则撤销一次只撤掉其中一条");
        assertTrue(definition.contains("KEY `idx_grantee_active` (`tenant_id`, `grantee_user_id`, `revoked_at`, `conversation_id`)"),
                "「分享给我」列表按被分享人查，需要索引");
    }

    @Test
    void migrationCreatesTurnArtifactTableWithFrozenManifest() throws Exception {
        String definition = normalize(tableDefinition(read(MIGRATION), "conversation_turn_artifact"));
        assertTrue(definition.contains("`direction` VARCHAR(8) NOT NULL"), "INPUT / OUTPUT 必须分开，不能靠推断");
        assertTrue(definition.contains("`reference_mode` VARCHAR(16) NOT NULL"),
                "UPLOAD / SELECTED / MENTION / GENERATED 决定失败时的用户提示");
        assertTrue(definition.contains("`manifest_json` MEDIUMTEXT NOT NULL"),
                "清单在 Turn 创建时固化，后续同名文件更新不得回溯改写");
        assertTrue(definition.contains("UNIQUE KEY `uk_turn_artifact_direction` (`turn_id`, `artifact_id`, `direction`)"),
                "事件重投不能把同一文件在同一方向上挂两遍");
    }

    @Test
    void migrationCreatesActionPlanTableWithFrozenPayload() throws Exception {
        String definition = normalize(tableDefinition(read(MIGRATION), "conversation_action_plan"));
        assertTrue(definition.contains("`owner_user_id` BIGINT NOT NULL"), "只有此人能确认，跨 Owner 确认必须失败");
        assertTrue(definition.contains("`canonical_payload_json` MEDIUMTEXT NOT NULL"), "参数冻结后不可再改");
        assertTrue(definition.contains("`payload_sha256` CHAR(64) NOT NULL"), "确认时必须回传同一哈希，防篡改");
        assertTrue(definition.contains("`expires_at` DATETIME NOT NULL"), "TTL 到期即 EXPIRED，不可确认");
        assertTrue(definition.contains("`consumed_at` DATETIME NULL"), "原子消费标记，非空即不可再次执行");
        assertTrue(definition.contains("UNIQUE KEY `uk_plan_hash` (`tenant_id`, `conversation_id`, `payload_sha256`)"),
                "同一会话内重复提案要收敛到同一计划，避免用户看到两份一样的确认卡");
        assertTrue(definition.contains("KEY `idx_plan_expiry` (`status`, `expires_at`, `id`)"), "过期扫描需要索引");
    }

    @Test
    void migrationCreatesActionStepTableWithUniqueStepNo() throws Exception {
        String definition = normalize(tableDefinition(read(MIGRATION), "conversation_action_step"));
        assertTrue(definition.contains("`tool_name` VARCHAR(128) NOT NULL"), "工具名冻结，执行时不接受替换");
        assertTrue(definition.contains("`arguments_sha256` CHAR(64) NOT NULL"), "逐步参数也要可校验");
        assertTrue(definition.contains("`idempotent` TINYINT NOT NULL DEFAULT 0"), "只有标记幂等的失败步骤可重试");
        assertTrue(definition.contains("UNIQUE KEY `uk_plan_step` (`plan_id`, `step_no`)"),
                "步骤号唯一，重试与部分失败恢复才不会插出重复步骤");
        assertTrue(definition.contains("`result_summary_json` MEDIUMTEXT NULL"), "结果摘要脱敏，不含正文与 Secret");
    }

    /** 四张新表在两份 SQL 里必须逐字一致，AUTO_INCREMENT=10000 是仓库统一约定。 */
    @Test
    void schemaFileStaysInSyncWithMigration() throws Exception {
        String migration = read(MIGRATION);
        String schema = read(SCHEMA);
        for (String table : NEW_TABLES) {
            assertEquals(tableDefinition(migration, table), tableDefinition(schema, table),
                    "autowonder-schema.sql 的 " + table + " 建表语句必须与 migration 逐字一致");
            assertTrue(normalize(tableDefinition(migration, table)).contains("AUTO_INCREMENT=10000"),
                    table + " 自增必须从 10000 起，避免小 ID 与业务编号混淆");
        }
    }
}
