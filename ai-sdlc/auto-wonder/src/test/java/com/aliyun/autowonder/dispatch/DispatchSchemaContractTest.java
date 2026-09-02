package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchSchemaContractTest {

    private static final Path SCHEDULED_TASK_MIGRATION =
            Path.of("docs/migration/V041__scheduled_task.sql");
    private static final Pattern CANONICAL_WIDTH = Pattern.compile(
            "(?is)CREATE TABLE IF NOT EXISTS `dispatch`\\s*\\(.*?"
                    + "`status`\\s+VARCHAR\\((\\d+)\\)");

    @Test
    void dispatchStatusStorageCanHoldEveryDefinedStatus() throws Exception {
        int requiredWidth = Arrays.stream(DispatchStatus.class.getDeclaredFields())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .mapToInt(this::statusLength)
                .max()
                .orElseThrow();

        assertCapacity(Files.readString(Path.of("docs/autowonder-schema.sql")),
                CANONICAL_WIDTH, requiredWidth, "canonical dispatch schema");
    }

    @Test
    void scheduledTaskMigrationAddsSourceAwareCompatibilityColumnsAndIndexes() throws Exception {
        String migration = Files.readString(SCHEDULED_TASK_MIGRATION);

        List<String> tables = List.of("dispatch", "artifact", "workitem_comment",
                "workitem_comment_mention", "workitem_comment_delivery");
        List<String> indexes = List.of("idx_dispatch_source", "idx_artifact_source",
                "idx_comment_source", "idx_mention_source", "idx_delivery_source");
        for (int i = 0; i < tables.size(); i++) {
            String alteration = alterTableDefinition(migration, tables.get(i));
            assertTrue(alteration.contains(
                    "ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER `tenant_id`"),
                    tables.get(i) + " must add the compatible source default");
            assertTrue(alteration.contains("ADD KEY `" + indexes.get(i)
                            + "` (`tenant_id`, `source_type`, `workitem_id`, `id`)"),
                    tables.get(i) + " must add its source-aware index");
        }
        String workitemAlteration = alterTableDefinition(migration, "workitem");
        assertTrue(workitemAlteration.contains("ADD COLUMN `origin_type` VARCHAR(32) DEFAULT NULL"));
        assertTrue(workitemAlteration.contains(
                "ADD COLUMN `origin_id` BIGINT UNSIGNED DEFAULT NULL"));
        assertTrue(workitemAlteration.contains(
                "ADD KEY `idx_workitem_origin` (`tenant_id`, `origin_type`, `origin_id`)"));
        assertTrue(!migration.contains("ALTER TABLE `guidance`"),
                "GuidanceDO is stored in workitem_comment_delivery, not a guidance table");
    }

    @Test
    void canonicalSchemaMirrorsSourceAwareCompatibilityColumns() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));

        assertTableContract(schema, "dispatch", "`source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM'",
                "`idx_dispatch_source` (`tenant_id`, `source_type`, `workitem_id`, `id`)");
        assertTableContract(schema, "artifact", "`source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM'",
                "`idx_artifact_source` (`tenant_id`, `source_type`, `workitem_id`, `id`)");
        assertTableContract(schema, "workitem_comment", "`source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM'",
                "`idx_comment_source` (`tenant_id`, `source_type`, `workitem_id`, `id`)");
        assertTableContract(schema, "workitem_comment_mention",
                "`source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM'",
                "`idx_mention_source` (`tenant_id`, `source_type`, `workitem_id`, `id`)");
        assertTableContract(schema, "workitem_comment_delivery",
                "`source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM'",
                "`idx_delivery_source` (`tenant_id`, `source_type`, `workitem_id`, `id`)");
        assertTableContract(schema, "workitem", "`origin_type` VARCHAR(32) DEFAULT NULL",
                "`origin_id` BIGINT UNSIGNED DEFAULT NULL",
                "`idx_workitem_origin` (`tenant_id`, `origin_type`, `origin_id`)");
    }

    @Test
    void legacyAndNamespacedWorkitemKeysShareOneDatabaseUniquenessDomain() throws Exception {
        String migration = Files.readString(SCHEDULED_TASK_MIGRATION);
        String canonical = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String migrationDispatch = alterTableDefinition(migration, "dispatch");
        String canonicalDispatch = tableDefinition(canonical, "dispatch");

        for (String definition : List.of(migrationDispatch, canonicalDispatch)) {
            assertTrue(definition.contains(
                    "`normalized_idempotency_key` VARCHAR(137) GENERATED ALWAYS AS"));
            assertTrue(definition.contains("source_type = 'WORKITEM'"));
            assertTrue(definition.contains(
                    "idempotency_key REGEXP '^[0-9]+:[0-9]+:[0-9]+$'"));
            assertTrue(definition.contains("CONCAT('WORKITEM:', idempotency_key)"));
            assertTrue(definition.contains("STORED"));
            assertTrue(definition.contains(
                    "UNIQUE KEY `uk_dispatch_normalized_idempotency` (`tenant_id`, `normalized_idempotency_key`)"));
        }
        assertEquals(generatedIdempotencyDefinition(migration),
                generatedIdempotencyDefinition(canonical),
                "migration and canonical schema must mirror the generated column verbatim");
    }

    @Test
    void executionSourceDefaultsOnlyMissingValuesToWorkitem() {
        assertEquals(ExecutionSourceType.WORKITEM, ExecutionSourceType.valueOrWorkitem(null));
        assertEquals(ExecutionSourceType.WORKITEM, ExecutionSourceType.valueOrWorkitem("  "));
        assertEquals(ExecutionSourceType.SCHEDULED_TASK,
                ExecutionSourceType.valueOrWorkitem("SCHEDULED_TASK"));
        assertEquals(ExecutionSourceType.SCHEDULED_TASK_RUN,
                ExecutionSourceType.valueOrWorkitem("SCHEDULED_TASK_RUN"));
    }

    private int statusLength(Field field) {
        try {
            return ((String) field.get(null)).length();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertCapacity(String sql, Pattern pattern, int requiredWidth, String source) {
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), source + " must declare dispatch.status width");
        int actualWidth = Integer.parseInt(matcher.group(1));
        assertTrue(actualWidth >= requiredWidth,
                source + " width " + actualWidth + " is smaller than required " + requiredWidth);
    }

    private void assertTableContract(String schema, String table, String... fragments) {
        String definition = tableDefinition(schema, table);
        for (String fragment : fragments) {
            assertTrue(definition.contains(fragment), table + " must declare " + fragment);
        }
    }

    private String tableDefinition(String schema, String table) {
        Pattern tablePattern = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + Pattern.quote(table) + "`\\s*\\((.*?)\\)"
                        + "\\s*ENGINE=");
        Matcher matcher = tablePattern.matcher(schema);
        assertTrue(matcher.find(), "canonical schema must declare " + table);
        return matcher.group(1).replaceAll("\\s+", " ");
    }

    private String alterTableDefinition(String migration, String table) {
        Pattern pattern = Pattern.compile(
                "(?is)ALTER TABLE `" + Pattern.quote(table) + "`(.*?);");
        Matcher matcher = pattern.matcher(migration);
        assertTrue(matcher.find(), "migration must alter " + table);
        return matcher.group(1).replaceAll("\\s+", " ");
    }

    private String generatedIdempotencyDefinition(String sql) {
        // Reluctant quantifier: with DOTALL a greedy (.*) would run past this column's
        // closing ") STORED" and swallow every later generated column in the file.
        Pattern pattern = Pattern.compile(
                "(?is)`normalized_idempotency_key`\\s+VARCHAR\\(137\\)\\s+"
                        + "GENERATED ALWAYS AS\\s*\\((.*?)\\)\\s+STORED");
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), "missing normalized idempotency generated column");
        return matcher.group(0).replaceAll("\\s+", " ").trim();
    }
}
