package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表语句的契约测试。migration 与 autowonder-schema.sql 必须一致，
 * 否则新环境初始化出来的表结构和存量环境迁移出来的不一样。
 */
class ConversationElicitationSchemaContractTest {

    private static final String MIGRATION = "docs/migration/V049__conversation_elicitation.sql";
    private static final String SCHEMA = "docs/autowonder-schema.sql";
    private static final String TABLE = "agent_conversation_elicitation";

    private String read(String relative) throws Exception {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesElicitationTableWithIdempotencyKey() throws Exception {
        String definition = normalize(tableDefinition(read(MIGRATION)));
        assertTrue(definition.contains("CREATE TABLE IF NOT EXISTS `" + TABLE + "`"),
                "migration 必须建 " + TABLE + " 表");
        assertTrue(definition.contains(
                        "UNIQUE KEY `uk_conv_request` (`tenant_id`, `conversation_id`, `request_id`)"),
                "requestId 唯一键是事件重投幂等的基础");
        assertTrue(definition.contains("`schema_json` MEDIUMTEXT"),
                "requestedSchema 原样存储，长度不可控");
        assertTrue(definition.contains("`answer_json` MEDIUMTEXT"),
                "回答同样是任意 JSON，长度不可控");
        assertTrue(definition.contains("`status` VARCHAR(16)"),
                "status 需容纳 PENDING/ANSWERED/DECLINED/EXPIRED/CANCELED");
        assertTrue(definition.contains("`request_id` VARCHAR(64) NOT NULL"),
                "requestId 是执行器生成的 32 位十六进制，64 足够且留余量");
        assertTrue(definition.contains("`turn_id` BIGINT NOT NULL"),
                "卡片必须归属到具体轮次，回答帧要带 turnId 给执行器定位");
        assertTrue(definition.contains("KEY `idx_turn` (`tenant_id`, `turn_id`)"),
                "取消联动按轮次查挂起卡片，需要索引");
        assertTrue(definition.contains("KEY `idx_pending_expiry` (`status`, `gmt_create`)"),
                "过期扫描需要索引，否则定时任务会全表扫");
        assertTrue(definition.contains("AUTO_INCREMENT=10000"),
                "仓库统一约定自增从 10000 起，避免小 ID 与业务编号混淆");
    }

    @Test
    void schemaFileStaysInSyncWithMigration() throws Exception {
        String current = tableDefinition(read(SCHEMA));
        String addition = read("docs/migration/V098__conversation_answer_delivery.sql");
        for (String column : java.util.List.of("answer_protocol_version", "dispatch_attempt", "executor_id",
                "answer_id", "delivery_status", "delivery_deadline", "next_delivery_at")) {
            assertTrue(addition.contains("ADD COLUMN `" + column + "`"));
            assertTrue(current.contains("`" + column + "`"));
            current = current.replaceAll("(?m)^  `" + column + "`[^\\n]*\\n", "");
        }
        assertTrue(addition.contains("ADD KEY `idx_answer_delivery_due`"));
        current = current.replace(",\n  KEY `idx_answer_delivery_due` (`delivery_status`, `next_delivery_at`, `id`)", "");
        current = current.replace(",\n  KEY `idx_answer_conversation_recent` (`tenant_id`, `conversation_id`, `id`)", "");
        assertEquals(tableDefinition(read(MIGRATION)), current,
                "autowonder-schema.sql 的建表语句必须与 migration 逐字一致");
    }

    private String tableDefinition(String sql) {
        Pattern pattern = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + Pattern.quote(TABLE) + "`\\s*\\(.*?\\)"
                        + "\\s*ENGINE=InnoDB.*?;");
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), "schema must declare " + TABLE);
        return matcher.group();
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ");
    }
}
