package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskSchemaContractTest {

    private static final Path MIGRATION =
            Path.of("docs/migration/V041__scheduled_task.sql");

    @Test
    void createsTaskRunAndSourceIsolationContracts() throws Exception {
        String migration = Files.readString(MIGRATION);

        assertTableContains(migration, "scheduled_task",
                "`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT",
                "`workspace_id` BIGINT UNSIGNED NOT NULL",
                "`name` VARCHAR(256) NOT NULL",
                "`instruction_md` MEDIUMTEXT NOT NULL",
                "`squad_id` BIGINT UNSIGNED NOT NULL",
                "`initial_agent_id` BIGINT UNSIGNED NOT NULL",
                "`schedule_type` VARCHAR(16) NOT NULL",
                "`run_at` DATETIME(3) DEFAULT NULL",
                "`cron_expression` VARCHAR(128) DEFAULT NULL",
                "`timezone` VARCHAR(64) NOT NULL",
                "`session_mode` VARCHAR(16) NOT NULL DEFAULT 'ISOLATED'",
                "`overlap_policy` VARCHAR(16) NOT NULL DEFAULT 'SKIP'",
                "`misfire_policy` VARCHAR(16) NOT NULL DEFAULT 'FIRE_LATEST'",
                "`start_deadline_seconds` INT NOT NULL DEFAULT 21600",
                "`affinity_timeout_seconds` INT NOT NULL DEFAULT 1800",
                "`status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'",
                "`next_fire_at` DATETIME(3) DEFAULT NULL",
                "`last_fire_at` DATETIME(3) DEFAULT NULL",
                "`gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)",
                "`gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)",
                "`creator_id` BIGINT UNSIGNED NOT NULL",
                "`modifier_id` BIGINT UNSIGNED DEFAULT NULL",
                "`is_deleted` TINYINT NOT NULL DEFAULT 0",
                "`version` INT NOT NULL DEFAULT 0",
                "KEY `idx_scheduled_task_due` (`status`, `is_deleted`, `next_fire_at`, `id`)",
                "KEY `idx_scheduled_task_owner` (`workspace_id`, `creator_id`, `status`, `id`)");
        assertTableContains(migration, "scheduled_task_run",
                "`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT",
                "`workspace_id` BIGINT UNSIGNED NOT NULL",
                "`scheduled_task_id` BIGINT UNSIGNED NOT NULL",
                "`trigger_key` VARCHAR(256) NOT NULL",
                "`trigger_type` VARCHAR(16) NOT NULL",
                "`scheduled_at` DATETIME(3) NOT NULL",
                "`started_at` DATETIME(3) DEFAULT NULL",
                "`finished_at` DATETIME(3) DEFAULT NULL",
                "`status` VARCHAR(32) NOT NULL COMMENT 'QUEUED/STARTING/WAITING_EXECUTOR/RUNNING/WAITING_HUMAN/PAUSED/SUCCEEDED/FAILED/TIMED_OUT/CANCELED/SKIPPED'",
                "`skip_reason` VARCHAR(32) DEFAULT NULL",
                "`squad_id` BIGINT UNSIGNED NOT NULL",
                "`initial_agent_id` BIGINT UNSIGNED NOT NULL",
                "`current_agent_id` BIGINT UNSIGNED DEFAULT NULL",
                "`sdlc_id` BIGINT UNSIGNED DEFAULT NULL",
                "`current_step_id` BIGINT UNSIGNED DEFAULT NULL",
                "`session_mode` VARCHAR(16) NOT NULL",
                "`resume_from_run_id` BIGINT UNSIGNED DEFAULT NULL",
                "`degraded_resume` TINYINT NOT NULL DEFAULT 0",
                "`degraded_reason` VARCHAR(512) DEFAULT NULL",
                "`execution_snapshot_json` JSON NOT NULL",
                "`result_summary` MEDIUMTEXT DEFAULT NULL",
                "`error` VARCHAR(1024) DEFAULT NULL",
                "`owner_id` BIGINT UNSIGNED NOT NULL",
                "`creator_id` BIGINT UNSIGNED NOT NULL",
                "`modifier_id` BIGINT UNSIGNED DEFAULT NULL",
                "`gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)",
                "`gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)",
                "`version` INT NOT NULL DEFAULT 0",
                "UNIQUE KEY `uk_scheduled_task_trigger` (`workspace_id`, `trigger_key`)",
                "KEY `idx_scheduled_task_run_task` (`workspace_id`, `scheduled_task_id`, `id`)",
                "KEY `idx_scheduled_task_run_status` (`workspace_id`, `status`, `scheduled_at`, `id`)",
                "KEY `idx_scheduled_task_run_resume` (`workspace_id`, `resume_from_run_id`)",
                "KEY `idx_scheduled_task_run_recovery` (`status`, `gmt_modified`, `id`)",
                "KEY `idx_scheduled_task_run_queue` (`workspace_id`, `scheduled_task_id`, `status`, `scheduled_at`, `id`)",
                "KEY `idx_scheduled_task_run_health` (`workspace_id`, `scheduled_task_id`, `finished_at`, `status`)");
        assertFalse(tableDefinition(migration, "scheduled_task").contains("tenant_id"));
        assertFalse(tableDefinition(migration, "scheduled_task_run").contains("tenant_id"));
        assertTrue(migration.contains(
                "ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM'"));
        assertTrue(migration.contains("ADD COLUMN `origin_type` VARCHAR(32) DEFAULT NULL"));
        assertTrue(migration.contains("ADD COLUMN `origin_id` BIGINT UNSIGNED DEFAULT NULL"));
    }

    @Test
    void canonicalSchemaMirrorsScheduledTaskTables() throws Exception {
        String migration = Files.readString(MIGRATION);
        String canonical = Files.readString(Path.of("docs/autowonder-schema.sql"));

        assertEquals(tableDefinition(migration, "scheduled_task"),
                tableDefinition(canonical, "scheduled_task"));
        assertEquals(tableDefinition(migration, "scheduled_task_run"),
                tableDefinition(canonical, "scheduled_task_run"));
    }

    private void assertTableContains(String sql, String table, String... expectedFragments) {
        String definition = normalize(tableDefinition(sql, table));
        for (String expected : expectedFragments) {
            assertTrue(definition.contains(expected), table + " must declare " + expected);
        }
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
}
