package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.tenant.TenantTables;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * squad_copy_operation 的迁移脚本、全量 schema 与租户改写白名单必须始终对齐：
 * 复制请求天生跨空间（源空间发起、目标空间回填），一旦被 TenantInterceptor 追加单空间条件，
 * 回放与补偿查询会在运行期直接报未知列/查不到记录。
 */
class SquadCopyOperationSchemaContractTest {

    private static final Path MIGRATION = Path.of("docs/migration/V086__squad_copy_operation.sql");
    private static final Path SCHEMA = Path.of("docs/autowonder-schema.sql");

    @Test
    void migrationAndFullSchemaCarryTheSameTable() throws Exception {
        assertTrue(Files.exists(MIGRATION), "missing migration " + MIGRATION);
        Set<String> migrationColumns = columnsOf(tableDefinition(Files.readString(MIGRATION)));
        Set<String> schemaColumns = columnsOf(tableDefinition(Files.readString(SCHEMA)));

        assertEquals(migrationColumns, schemaColumns,
                "运维按 migration 建表、排查看全量 schema，两者列不一致会直接误判线上结构");
        assertEquals(Set.of("id", "source_workspace_id", "source_squad_id", "user_id", "request_id",
                "target_workspace_id", "request_fingerprint", "source_snapshot_ref", "status",
                "target_squad_id", "error_code", "error_message", "resource_cleanup_json",
                "gmt_create", "gmt_modified"), schemaColumns);
    }

    @Test
    void uniqueKeyStopsDuplicateRequestTargetPairs() throws Exception {
        String expected = "UNIQUE KEY uk_squad_copy_request "
                + "(source_workspace_id, user_id, request_id, target_workspace_id)";

        assertTrue(tableDefinition(Files.readString(MIGRATION)).contains(expected),
                "重复 (用户, 源空间, 请求标识, 目标空间) 必须被唯一键挡住，实际: "
                        + tableDefinition(Files.readString(MIGRATION)));
        assertTrue(tableDefinition(Files.readString(SCHEMA)).contains(expected),
                "全量 schema 必须保留同一唯一键");
    }

    @Test
    void requestIdentityIsCaseSensitive() throws Exception {
        // request_id 是客户端生成的标识：ascii_bin 排序规则保证大小写不同的标识是不同请求，
        // 避免 ci 规则把 "AbC" 与 "abc" 判为重复而互相遮蔽幂等结果。
        assertTrue(tableDefinition(Files.readString(MIGRATION))
                        .contains("COLLATE ascii_bin"),
                "request_id 与摘要列必须大小写敏感");
        assertTrue(tableDefinition(Files.readString(SCHEMA))
                        .contains("COLLATE ascii_bin"),
                "request_id 与摘要列必须大小写敏感");
    }

    @Test
    void tableStaysOutsideTenantRewriting() throws Exception {
        assertFalse(TenantTables.TABLES.contains("squad_copy_operation"),
                "复制记录跨空间查询，纳入租户改写会让回放与补偿语句带上错误的单空间条件");
    }

    private static String tableDefinition(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS squad_copy_operation\\s*\\((.*?)\\)\\s*ENGINE=")
                .matcher(sql);
        assertTrue(matcher.find(), "missing canonical table squad_copy_operation");
        return matcher.group(1);
    }

    /** 只取列定义行：索引行以 PRIMARY / UNIQUE / KEY 开头，不会命中行首小写列名。 */
    private static Set<String> columnsOf(String tableDefinition) {
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*([a-z][a-z_0-9]*)\\s").matcher(tableDefinition);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        assertFalse(columns.isEmpty(), "no column parsed out of: " + tableDefinition);
        return columns;
    }
}
