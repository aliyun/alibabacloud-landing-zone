package com.aliyun.autowonder.user;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * user_setting 的 DDL、全量 schema 与 mapper 三者必须始终对齐。
 * 三处各写一遍、彼此不校验，是这套代码库里已经踩过的坑（见 V019__align_system_setting_columns.sql）。
 */
class UserSettingSchemaContractTest {

    private static final Path MIGRATION = Path.of("docs/migration/V066__user_setting.sql");
    private static final Path SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final Path MAPPER = Path.of("src/main/resources/mapping/UserSettingDao.xml");

    @Test
    void migrationCreatesTheTableWithAUserScopedUniqueKey() throws Exception {
        assertTrue(Files.exists(MIGRATION), "missing migration " + MIGRATION);
        String table = tableDefinition(Files.readString(MIGRATION), "user_setting");

        assertTrue(table.contains("`user_id`"), "user_setting must be owned by a user");
        assertTrue(table.contains("`setting_key`"), "user_setting must be a generic key-value table");
        assertTrue(table.contains("`value_json`"), "user_setting must carry its value as JSON");
        assertTrue(table.contains("UNIQUE KEY `uk_user_setting` (`user_id`, `setting_key`)"),
                "AC-08 requires a unique index on user + key, got: " + table);
    }

    @Test
    void fullSchemaCarriesTheSameTable() throws Exception {
        String table = tableDefinition(Files.readString(SCHEMA), "user_setting");

        assertTrue(table.contains("UNIQUE KEY `uk_user_setting` (`user_id`, `setting_key`)"),
                "docs/autowonder-schema.sql must hold the full user_setting definition, got: " + table);
    }

    @Test
    void migrationAndFullSchemaAgreeOnEveryColumn() throws Exception {
        Set<String> migrationColumns = columnsOf(tableDefinition(Files.readString(MIGRATION), "user_setting"));
        Set<String> schemaColumns = columnsOf(tableDefinition(Files.readString(SCHEMA), "user_setting"));

        assertEquals(migrationColumns, schemaColumns,
                "运维按 migration 建表、排查看全量 schema，两者列不一致会直接误判线上结构");
        assertEquals(Set.of("id", "user_id", "setting_key", "value_json",
                "gmt_create", "gmt_modified", "creator_id", "modifier_id", "is_deleted"), schemaColumns);
    }

    @Test
    void userSettingIsAGlobalTableWithoutWorkspaceScoping() throws Exception {
        // 偏好属于用户本人：一旦带上 tenant_id / workspace_id，同一用户换个空间就会读回默认发送方式，
        // 直接违反 FR-009「跨会话生效」。全局表约定见 docs/autowonder-schema.sql 头部与 `user` 表。
        String migration = Files.readString(MIGRATION);
        String table = tableDefinition(Files.readString(SCHEMA), "user_setting");

        assertFalse(migration.contains("tenant_id"), "migration must not scope a user preference by tenant");
        assertFalse(migration.contains("workspace_id"), "migration must not scope a user preference by workspace");
        assertFalse(table.contains("tenant_id"), "schema must not scope a user preference by tenant");
        assertFalse(table.contains("workspace_id"), "schema must not scope a user preference by workspace");
    }

    @Test
    void mapperUsesActualUserSettingColumns() throws Exception {
        String mapper = Files.readString(MAPPER);

        assertTrue(mapper.contains("user_id"));
        assertTrue(mapper.contains("setting_key"));
        assertTrue(mapper.contains("value_json"));
        assertTrue(mapper.contains("INSERT INTO user_setting"));
        assertFalse(mapper.contains("#{key}"), "statement params must be named settingKey, not key");
        assertFalse(mapper.contains("#{group}"), "user_setting has no group column");
        assertFalse(mapper.contains("tenant_id"), "user_setting is a global table");
    }

    @Test
    void uniqueKeyLookupSeesSoftDeletedRowsSoUpsertCanReviveInsteadOfClashing() throws Exception {
        // uk_user_setting 不含 is_deleted，软删行仍占着唯一键槽位。findByUk 若跟着过滤 is_deleted，
        // 「删除偏好 → 再改一次发送方式」就会走 insert 撞唯一键，用户侧表现为 500。
        String findByUk = statement("findByUk", "select");

        assertTrue(findByUk.contains("user_id = #{userId}"), "findByUk must be scoped by user, got: " + findByUk);
        assertTrue(findByUk.contains("setting_key = #{settingKey}"), "got: " + findByUk);
        assertFalse(findByUk.contains("is_deleted"),
                "findByUk must not filter is_deleted, or a soft-deleted row becomes an unrevivable "
                        + "duplicate-key trap, got: " + findByUk);
    }

    @Test
    void publicReadsFilterSoftDeletedRows() throws Exception {
        assertTrue(statement("findLive", "select").contains("is_deleted = 0"),
                "findLive backs GET and must hide deleted preferences");
        assertTrue(statement("listByUser", "select").contains("is_deleted = 0"),
                "listByUser must hide deleted preferences");
    }

    @Test
    void updateRevivesTheRowItTouches() throws Exception {
        String update = statement("update", "update");

        assertTrue(update.contains("is_deleted = 0"),
                "update must clear the soft-delete flag so a revived row is readable again, got: " + update);
        assertTrue(update.contains("user_id = #{userId}"),
                "update must stay scoped to the owner, got: " + update);
    }

    @Test
    void softDeleteIsIdempotentAndOwnerScoped() throws Exception {
        String softDelete = statement("softDelete", "update");

        assertTrue(softDelete.contains("SET is_deleted = 1"), "got: " + softDelete);
        assertTrue(softDelete.contains("is_deleted = 0"),
                "the is_deleted = 0 guard makes a repeated delete write nothing, got: " + softDelete);
        assertTrue(softDelete.contains("user_id = #{userId}"),
                "softDelete must not be able to touch another user's row, got: " + softDelete);
    }

    private static String tableDefinition(String sql, String table) {
        Matcher matcher = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\((.*?)\\)\\s*ENGINE=")
                .matcher(sql);
        assertTrue(matcher.find(), "missing canonical table " + table);
        return matcher.group(1);
    }

    /** 只取列定义行：索引行以 PRIMARY / UNIQUE / KEY 开头，不会命中行首反引号。 */
    private static Set<String> columnsOf(String tableDefinition) {
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*`([a-z_0-9]+)`\\s").matcher(tableDefinition);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        assertFalse(columns.isEmpty(), "no column parsed out of: " + tableDefinition);
        return columns;
    }

    private static String statement(String id, String tag) throws IOException {
        String xml = new String(Files.readAllBytes(MAPPER), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "UserSettingDao.xml has no " + tag + " with id=" + id);
        int end = xml.indexOf("</" + tag + ">", start);
        assertTrue(end > start, "unterminated " + tag + " " + id);
        return xml.substring(start, end);
    }
}
