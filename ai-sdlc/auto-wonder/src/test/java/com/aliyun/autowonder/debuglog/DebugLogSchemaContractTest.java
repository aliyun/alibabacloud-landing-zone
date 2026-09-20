package com.aliyun.autowonder.debuglog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugLogSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final Path MIGRATION = Path.of("docs/migration/V058__squad_debug_log.sql");

    @Test
    void migrationAddsFreezeColumnsAndDebugLogTable() throws Exception {
        assertTrue(Files.exists(MIGRATION), "V049 migration must exist");
        assertTrue(Files.exists(CANONICAL_SCHEMA), "canonical schema must exist");
        String migration = normalize(Files.readString(MIGRATION));

        // Decision 5: TINYINT (not TINYINT(1)) for squad.debug_log_enabled
        // Decision 10: AFTER clause for squad ALTER
        assertTrue(migration.contains("ALTER TABLE `squad` ADD COLUMN `debug_log_enabled`"
                        + " TINYINT NOT NULL DEFAULT 0 COMMENT '小队级 debug 日志收集开关' AFTER `status`"),
                "squad ALTER must add debug_log_enabled TINYINT AFTER `status`");

        // Decision 5: TINYINT (not TINYINT(1)) for dispatch.debug_log_enabled
        // Decision 10: AFTER clause for dispatch ALTER
        assertTrue(migration.contains("ALTER TABLE `dispatch` ADD COLUMN `debug_log_enabled`"
                        + " TINYINT NOT NULL DEFAULT 0 COMMENT '打包时冻结：本轮是否收集全量 debug 日志' AFTER `resume_mode`"),
                "dispatch ALTER must add debug_log_enabled TINYINT AFTER `resume_mode`");

        String debugLog = normalize(tableDefinition(migration, "debug_log"));
        assertContains(debugLog,
                "`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT",
                "`tenant_id` BIGINT UNSIGNED NOT NULL",
                "`source_type` VARCHAR(32) NOT NULL",
                "`source_id` BIGINT UNSIGNED NOT NULL",
                "`dispatch_id` BIGINT UNSIGNED NOT NULL",
                "`agent_id` BIGINT UNSIGNED NOT NULL",
                "`agent_version_id` BIGINT UNSIGNED",
                "`run_no` INT NOT NULL",
                // Decision 6: VARCHAR(32) symmetric with dispatch.status
                "`dispatch_status` VARCHAR(32) NOT NULL",
                "`object_key` VARCHAR(512) NOT NULL",
                "`size_bytes` BIGINT",
                // Decision 4: VARCHAR(80) absorbs optional sha256: prefix
                "`sha256` VARCHAR(80)",
                // Decision 5: TINYINT (not TINYINT(1))
                "`truncated` TINYINT NOT NULL DEFAULT 0",
                "`upload_channel` VARCHAR(16)",
                "`status` VARCHAR(16) NOT NULL",
                "`error_message` VARCHAR(1024)",
                // Decision 7: gmt_create/gmt_modified after all business columns
                "`gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)",
                "`gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)",
                "PRIMARY KEY (`id`)",
                // Decision 2: uk_dispatch stays without tenant_id (daemon path has no tenant context)
                "UNIQUE KEY `uk_dispatch` (`dispatch_id`)",
                // Decision 3: loud failure on run_no collision instead of silent OSS overwrite
                "UNIQUE KEY `uk_source_agent_run` (`source_type`, `source_id`, `agent_id`, `run_no`)",
                // Decision 2: tenant-leading indexes
                "KEY `idx_source` (`tenant_id`, `source_type`, `source_id`)",
                "KEY `idx_agent` (`tenant_id`, `agent_id`, `gmt_create`)",
                // Decision 1: S11 reconciliation scan WHERE status='PENDING' AND gmt_modified < ?
                "KEY `idx_pending_reconcile` (`status`, `gmt_modified`)",
                // Decision 10: table-level COMMENT
                "ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='小队 debug 日志登记'");

        // Decision 7: column order — gmt_create must follow error_message
        int errorIdx = debugLog.indexOf("`error_message`");
        int gmtCreateIdx = debugLog.indexOf("`gmt_create`");
        assertTrue(errorIdx >= 0 && gmtCreateIdx > errorIdx,
                "gmt_create must appear after error_message (repo column-order convention)");

        // Decision 8: object_key COMMENT must document both layouts
        assertTrue(debugLog.contains("debug/{workitemId}/{roleCode}-run-{n}.log.gz"),
                "object_key COMMENT must include WORKITEM layout");
        assertTrue(debugLog.contains("debug/scheduled-{scheduledTaskId}-run-{runId}/{roleCode}-run-{n}.log.gz"),
                "object_key COMMENT must include SCHEDULED_TASK_RUN layout");

        // Decision 4: sha256 COMMENT explaining hex/prefix absorption
        assertTrue(debugLog.contains("`sha256` VARCHAR(80) DEFAULT NULL COMMENT"),
                "sha256 must be VARCHAR(80) with COMMENT");
    }

    @Test
    void canonicalSchemaMirrorsMigrationExactly() throws Exception {
        assertTrue(Files.exists(CANONICAL_SCHEMA), "canonical schema must exist");
        assertTrue(Files.exists(MIGRATION), "V049 migration must exist");
        String migration = Files.readString(MIGRATION);
        String canonical = Files.readString(CANONICAL_SCHEMA);

        // Migration and canonical debug_log definitions must be identical after normalization
        assertEquals(normalize(tableDefinition(migration, "debug_log")),
                normalize(tableDefinition(canonical, "debug_log")),
                "canonical schema must declare debug_log exactly as V049 does");

        // Decision 5: squad.debug_log_enabled TINYINT (not TINYINT(1))
        assertTrue(normalize(tableDefinition(canonical, "squad"))
                .contains("`debug_log_enabled` TINYINT NOT NULL DEFAULT 0"),
                "squad.debug_log_enabled must be TINYINT (not TINYINT(1))");
        // Decision 5: dispatch.debug_log_enabled TINYINT (not TINYINT(1))
        assertTrue(normalize(tableDefinition(canonical, "dispatch"))
                .contains("`debug_log_enabled` TINYINT NOT NULL DEFAULT 0"),
                "dispatch.debug_log_enabled must be TINYINT (not TINYINT(1))");
    }

    private static void assertContains(String normalizedDefinition, String... expectedFragments) {
        for (String expected : expectedFragments) {
            assertTrue(normalizedDefinition.contains(expected),
                    () -> "debug_log must declare " + expected + ", got: " + normalizedDefinition);
        }
    }

    private static String tableDefinition(String sql, String table) {
        Matcher matcher = Pattern.compile(
                        "(?is)CREATE TABLE IF NOT EXISTS `" + Pattern.quote(table) + "`\\s*\\(.*?\\)"
                                + "\\s*ENGINE=InnoDB.*?;")
                .matcher(sql);
        assertTrue(matcher.find(), "SQL must define table " + table);
        return matcher.group();
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ");
    }
}
