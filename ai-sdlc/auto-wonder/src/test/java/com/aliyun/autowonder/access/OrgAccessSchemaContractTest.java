package com.aliyun.autowonder.access;

import com.aliyun.autowonder.tenant.TenantTables;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgAccessSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final List<String> LEGACY_RBAC_TABLES =
            List.of("permission", "role", "role_permission", "member_role");

    @Test
    void canonicalSchemaContainsOnlyTheLightweightOrganizationAccessModel() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);

        assertMatches(schema, "(?is)CREATE TABLE IF NOT EXISTS `org_member`\\s*\\(.*?"
                + "`access_level`\\s+VARCHAR\\(16\\)\\s+NOT NULL\\s+DEFAULT\\s+'READ_ONLY'.*?"
                + "`identity_tags`\\s+JSON(?:\\s+DEFAULT\\s+NULL)?.*?"
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

}
