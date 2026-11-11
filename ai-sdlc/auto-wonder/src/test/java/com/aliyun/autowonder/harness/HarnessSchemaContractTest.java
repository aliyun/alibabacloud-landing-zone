package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.tenant.TenantTables;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新表必须满足仓库 canonical schema 契约：自增内存表从 10000 起，且登记租户表清单。 */
class HarnessSchemaContractTest {

    private static final List<String> TABLES = List.of("workspace_onboarding",
            "workspace_onboarding_step", "harness_snapshot", "harness_run", "harness_run_step",
            "workspace_readiness_check");

    @Test
    void everyNewTableStartsAtTenThousandInBothSchemaFiles() throws Exception {
        for (String path : List.of("docs/migration/V106__workspace_onboarding.sql", "docs/autowonder-schema.sql")) {
            String schema = Files.readString(Path.of(path));
            for (String table : TABLES) {
                String block = block(schema, table);
                assertTrue(block.contains("AUTO_INCREMENT=10000"),
                        path + " table " + table + " must start auto increment at 10000");
            }
        }
    }

    @Test
    void newTablesAreRegisteredAsTenantScoped() {
        for (String table : TABLES) {
            assertTrue(TenantTables.TABLES.contains(table), "TenantTables is missing " + table);
        }
    }

    private String block(String schema, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS `" + table + "`";
        int start = schema.indexOf(marker);
        assertTrue(start >= 0, "schema is missing table " + table);
        int end = schema.indexOf("\n);", start);
        return end < 0 ? schema.substring(start) : schema.substring(start, end + 3);
    }
}
