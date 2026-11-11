package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.tenant.TenantTables;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemDuplicateSchemaContractTest {

    @Test
    void featureSwitchIsGlobalAndNeverTenantScoped() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String table = tableDefinition(schema, "platform_feature_switch");

        assertFalse(table.contains("tenant_id"), "全局开关不能按空间隔离");
        assertFalse(TenantTables.TABLES.contains("platform_feature_switch"),
                "全局开关表不能进入租户改写白名单");
        assertTrue(schema.contains("AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='平台级功能开关'"));
    }

    @Test
    void duplicateCheckTableKeepsTenantScopeAndLeaseIndex() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String table = tableDefinition(schema, "workitem_duplicate_check");

        assertTrue(table.contains("`tenant_id`"));
        assertTrue(table.contains("`title_fingerprint`"));
        assertTrue(table.contains("`candidate_since`"));
        assertTrue(table.contains("idx_duplicate_check_scan"));
        assertFalse(TenantTables.TABLES.contains("workitem_duplicate_check"),
                "检测任务显式传 tenant_id，插入/更新不能依赖 SELECT 改写");
    }

    @Test
    void migrationMatchesCanonicalSchema() throws Exception {
        Path migration = Path.of("docs/migration/V103__workitem_duplicate_intent.sql");
        assertTrue(Files.exists(migration));

        String sql = Files.readString(migration);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `platform_feature_switch`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `workitem_duplicate_check`"));
    }

    @Test
    void singlePassMigrationAddsSnapshotAndCancelColumnsIdempotently() throws Exception {
        Path migration = Path.of("docs/migration/V105__workitem_duplicate_single_pass.sql");
        assertTrue(Files.exists(migration), "V076 迁移必须存在");

        String sql = Files.readString(migration);
        assertTrue(sql.contains("`candidate_snapshot_json`"));
        assertTrue(sql.contains("`cancel_pending`"));
        assertTrue(sql.contains("idx_duplicate_check_request"));
        // MySQL 无 ADD COLUMN IF NOT EXISTS，必须以 information_schema 判存在后执行才能重复运行。
        assertTrue(sql.contains("information_schema.COLUMNS"));
        assertTrue(sql.contains("information_schema.STATISTICS"));
        assertTrue(sql.contains("PREPARE stmt FROM @sql"));
        assertTrue(sql.contains("ALTER TABLE `workitem_duplicate_check` MODIFY COLUMN `match_json` MEDIUMTEXT"));
    }

    @Test
    void canonicalSchemaCarriesTheSinglePassColumns() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String table = tableDefinition(schema, "workitem_duplicate_check");

        assertTrue(table.contains("`candidate_snapshot_json` MEDIUMTEXT"));
        assertTrue(table.contains("`cancel_pending`     TINYINT         NOT NULL DEFAULT 0"));
        assertTrue(table.contains("`match_json`         MEDIUMTEXT"));
        assertTrue(table.contains("`title`              VARCHAR(256)"));
        assertTrue(table.contains("idx_duplicate_check_request"));
        assertTrue(table.contains("`request_id`"));
    }

    private static String tableDefinition(String schema, String table) {
        var matcher = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\((.*?)\\)\\s*ENGINE=")
                .matcher(schema);
        assertTrue(matcher.find(), "missing canonical table " + table);
        return matcher.group(1);
    }
}
