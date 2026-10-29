package com.aliyun.autowonder.dashboard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardPipelineHealthSchemaContractTest {

    private static final Path SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final Path MIGRATION =
            Path.of("docs/migration/V092__dashboard_pipeline_health_index.sql");
    private static final String INDEX = "idx_dispatch_formal_created (tenant_id, source_type, gmt_create, status)";

    @Test
    void canonicalSchemaDeclaresPipelineHealthIndex() throws Exception {
        String dispatch = withoutBackticks(tableDefinition(Files.readString(SCHEMA), "dispatch"));
        assertTrue(dispatch.contains("KEY " + INDEX),
                "canonical dispatch table must declare KEY " + INDEX);
    }

    @Test
    void migrationDeclaresTheSamePipelineHealthIndex() throws Exception {
        String migration = withoutBackticks(Files.readString(MIGRATION));
        assertTrue(migration.contains("ADD INDEX " + INDEX),
                "V069 must declare ADD INDEX " + INDEX);
    }

    private String tableDefinition(String sql, String table) {
        Matcher matcher = Pattern.compile(
                        "(?is)CREATE TABLE IF NOT EXISTS `" + Pattern.quote(table) + "`\\s*\\(.*?\\)"
                                + "\\s*ENGINE=InnoDB.*?;")
                .matcher(sql);
        assertTrue(matcher.find(), "missing CREATE TABLE for " + table);
        return matcher.group();
    }

    private String withoutBackticks(String sql) {
        return sql.replace("`", "").replaceAll("\\s+", " ");
    }
}
