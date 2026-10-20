package com.aliyun.autowonder.access;

import com.aliyun.autowonder.tenant.TenantTables;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAccessSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final List<String> LEGACY_RBAC_TABLES =
            List.of("permission", "role", "role_permission", "member_role");

    @Test
    void canonicalSchemaUsesWorkspaceRolesAndKeepsLegacyColumnsForRecovery() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);

        assertMatches(schema, "(?is)CREATE TABLE IF NOT EXISTS `org_member`\\s*\\(.*?"
                + "`access_level`\\s+VARCHAR\\(16\\)\\s+NOT NULL\\s+DEFAULT\\s+'READ_ONLY'.*?"
                + "`identity_tags`\\s+JSON(?:\\s+DEFAULT\\s+NULL)?.*?"
                + "`forbidden_domains`\\s+JSON(?:\\s+DEFAULT\\s+NULL)?.*?"
                + "\\)\\s+ENGINE=InnoDB\\s+AUTO_INCREMENT=10000");
        assertMatches(schema, "(?is)CREATE TABLE IF NOT EXISTS `mcp_access_token`\\s*\\(.*?"
                + "`user_id`\\s+BIGINT UNSIGNED\\s+NOT NULL.*?"
                + "KEY `idx_mcp_token_user`\\s*\\(`user_id`,\\s*`is_deleted`\\).*?"
                + "\\)\\s+ENGINE=InnoDB\\s+AUTO_INCREMENT=10000");
        String mcpTable = table(schema, "mcp_access_token");
        assertFalse(mcpTable.contains("tenant_id"),
                "personal mcp_access_token must not be organization scoped");
        assertFalse(mcpTable.contains("access_level"),
                "personal mcp_access_token must not cap access level");
        assertFalse(TenantTables.TABLES.contains("mcp_access_token"),
                "mcp_access_token is a personal asset and must leave the tenant whitelist");

        for (String table : LEGACY_RBAC_TABLES) {
            assertFalse(Pattern.compile(
                            "(?is)CREATE TABLE IF NOT EXISTS\\s+`" + table + "`")
                    .matcher(schema)
                    .find(), "canonical schema still creates legacy table " + table);
            assertFalse(TenantTables.TABLES.contains(table),
                    "TenantTables still contains legacy table " + table);
        }
    }

    @Test
    void roleMigrationPreservesDataAndAssignsOneRoleBeforeEnforcingNotNull() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);
        String role = table(schema, "workspace_role");
        assertTrue(role.contains("`read_only_domains` JSON"));
        assertTrue(role.contains("`version` INT NOT NULL DEFAULT 0"));
        assertTrue(role.contains("UNIQUE KEY `uk_workspace_role_name` (`tenant_id`, `active_name_key`)"));
        assertTrue(table(schema, "org_member").contains("`role_id` BIGINT UNSIGNED NOT NULL"));
        String sql = Files.readString(Path.of("docs/migration/V082__workspace_roles.sql"));
        assertInOrder(sql, "Stop application writes", "CREATE TABLE IF NOT EXISTS `workspace_role`",
                "ADD COLUMN `role_id` BIGINT UNSIGNED NULL", "START TRANSACTION",
                "INSERT INTO workspace_role", "CONCAT('MIGRATED_', m.id)", "SET m.role_id = r.id",
                "COMMIT", "MODIFY COLUMN `role_id` BIGINT UNSIGNED NOT NULL");
        assertTrue(sql.contains("WHEN m.user_id = o.owner_id THEN 'ADMIN'"));
        assertTrue(sql.contains("m.access_level = 'READ_WRITE'"));
        assertTrue(sql.contains("m.forbidden_domains, m.creator_id"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("DROP COLUMN"));
        assertFalse(sql.contains("SET m.access_level"));
        assertFalse(sql.contains("JSON_ARRAY("), "built-in roles must not duplicate the domain catalog");
    }

    @Test
    void roleMigrationTouchesOnlyTheRoleTableAndMemberBinding() throws Exception {
        String sql = Files.readString(Path.of("docs/migration/V082__workspace_roles.sql"))
                .replaceAll("(?m)--[^\\r\\n]*", "");
        assertFalse(Pattern.compile("(?i)\\b(DROP|DELETE|TRUNCATE|RENAME|REPLACE)\\b").matcher(sql).find());
        assertEquals(List.of("workspace_role"), Pattern.compile("(?i)CREATE TABLE IF NOT EXISTS `([^`]+)`")
                .matcher(sql).results().map(match -> match.group(1)).toList());
        assertEquals(List.of("org_member", "org_member"), Pattern.compile("(?i)ALTER TABLE `([^`]+)`")
                .matcher(sql).results().map(match -> match.group(1)).toList());
        for (String operation : List.of("ADD", "MODIFY")) {
            assertEquals(List.of("role_id"), Pattern.compile("(?i)" + operation + " COLUMN `([^`]+)`")
                    .matcher(sql).results().map(match -> match.group(1)).toList());
        }
        assertEquals(List.of("workspace_role", "workspace_role"), Pattern.compile("(?i)INSERT INTO (\\w+)")
                .matcher(sql).results().map(match -> match.group(1)).toList());
        assertEquals(List.of("org_member"), Pattern.compile("(?im)^UPDATE (\\w+)")
                .matcher(sql).results().map(match -> match.group(1)).toList());
        assertEquals(List.of("m.role_id = r.id, m.gmt_modified = m.gmt_modified"),
                Pattern.compile("(?is)\\bSET\\s+([^;]+);").matcher(sql).results()
                        .map(match -> match.group(1).trim()).toList());
    }

    @Test
    void everyCanonicalAutoIncrementTableStartsAtTenThousand() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);
        var tableMatcher = Pattern.compile(
                        "(?is)CREATE TABLE IF NOT EXISTS\\s+`([^`]+)`\\s*\\((.*?)\\)\\s*"
                                + "ENGINE=InnoDB([^;]*);")
                .matcher(schema);

        int tableCount = 0;
        while (tableMatcher.find()) {
            tableCount++;
            if (tableMatcher.group(2).toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT")) {
                assertTrue(tableMatcher.group(3).toUpperCase(Locale.ROOT)
                                .contains("AUTO_INCREMENT=10000"),
                        "canonical table " + tableMatcher.group(1)
                                + " must start AUTO_INCREMENT at 10000");
            }
        }
        assertTrue(tableCount > 0, "canonical schema must contain tables");
    }

    private static String table(String schema, String name) {
        var matcher = Pattern.compile(
                        "(?is)CREATE TABLE IF NOT EXISTS\\s+`" + name + "`\\s*\\((.*?)\\)\\s*"
                                + "ENGINE=InnoDB")
                .matcher(schema);
        assertTrue(matcher.find(), "canonical schema must define table " + name);
        return matcher.group(1);
    }

    private static void assertMatches(String sql, String regex) {
        assertTrue(Pattern.compile(regex).matcher(sql).find(),
                () -> "SQL contract did not match: " + regex);
    }

    private static void assertInOrder(String sql, String... fragments) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        int previous = -1;
        for (String fragment : fragments) {
            int current = normalized.indexOf(fragment.toLowerCase(Locale.ROOT), previous + 1);
            assertTrue(current > previous,
                    () -> "missing or out-of-order SQL fragment: " + fragment);
            previous = current;
        }
    }
}
