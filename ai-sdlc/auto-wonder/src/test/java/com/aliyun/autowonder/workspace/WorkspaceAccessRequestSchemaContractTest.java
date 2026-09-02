package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.tenant.TenantTables;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAccessRequestSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final Path MIGRATION =
            Path.of("docs/migration/V048__workspace_access_request.sql");

    @Test
    void migrationCreatesTableWithSinglePendingRequestGuard() throws Exception {
        assertTrue(Files.exists(MIGRATION), "V044 migration must exist");
        String definition = normalize(
                tableDefinition(Files.readString(MIGRATION), "workspace_access_request"));

        assertContains(definition,
                "`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT",
                "`tenant_id` BIGINT UNSIGNED NOT NULL",
                "`requester_id` BIGINT UNSIGNED NOT NULL",
                "`requested_level` VARCHAR(20) NOT NULL",
                "`status` VARCHAR(20) NOT NULL DEFAULT 'PENDING'",
                "`reviewer_id` BIGINT UNSIGNED NULL",
                "`reject_reason` VARCHAR(512) NULL",
                "`gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)",
                "`gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"
                        + " ON UPDATE CURRENT_TIMESTAMP(3)",
                "PRIMARY KEY (`id`)",
                // Lookup indexes are asserted as literals so dropping either one fails the build.
                "KEY `idx_tenant_status` (`tenant_id`, `status`)",
                "KEY `idx_requester` (`requester_id`, `status`)",
                "UNIQUE KEY `uk_workspace_access_request_pending`"
                        + " (`tenant_id`, `requester_id`, `pending_marker`)",
                "ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4",
                "COMMENT='工作空间权限申请'");
        assertPendingMarkerGuard(definition, "V044 migration");
    }

    @Test
    void canonicalSchemaCarriesTheSameTableAndPendingGuard() throws Exception {
        String migration = normalize(
                tableDefinition(Files.readString(MIGRATION), "workspace_access_request"));
        String canonical = normalize(
                tableDefinition(Files.readString(CANONICAL_SCHEMA), "workspace_access_request"));

        assertEquals(migration, canonical,
                "canonical schema must declare workspace_access_request exactly as V044 does");
        assertPendingMarkerGuard(canonical, "canonical schema");
    }

    @Test
    void workspaceAccessRequestStaysOffTenantWhitelistToAllowCrossWorkspaceReads() {
        // TenantTables feeds TenantSqlRewriter, which auto-injects `tenant_id = <current
        // workspace>` into SELECTs on listed tables; this feature must read a requester's
        // pending requests across all workspaces and filters by tenant_id explicitly instead.
        assertFalse(TenantTables.TABLES.contains("workspace_access_request"),
                "workspace_access_request must stay off the tenant whitelist "
                        + "so cross-workspace reads are not rewritten");
    }

    /**
     * The generated column is the library-level guarantee behind "one PENDING request per
     * (tenant_id, requester_id)", so it is matched with a pattern that tolerates optional
     * backticks around {@code status} while still pinning the CASE expression exactly.
     */
    private static void assertPendingMarkerGuard(String normalizedDefinition, String source) {
        assertTrue(Pattern.compile("(?i)`pending_marker` TINYINT GENERATED ALWAYS AS \\( ?"
                                + "CASE WHEN `?status`? = 'PENDING' THEN 1 ELSE NULL END ?\\)"
                                + " STORED")
                        .matcher(normalizedDefinition)
                        .find(),
                () -> source + " must declare the `pending_marker` generated column, got: "
                        + normalizedDefinition);
    }

    private static void assertContains(String normalizedDefinition, String... expectedFragments) {
        for (String expected : expectedFragments) {
            assertTrue(normalizedDefinition.contains(expected),
                    () -> "workspace_access_request must declare " + expected + ", got: "
                            + normalizedDefinition);
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
