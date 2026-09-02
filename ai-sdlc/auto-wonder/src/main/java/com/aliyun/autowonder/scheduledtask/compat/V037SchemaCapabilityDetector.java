package com.aliyun.autowonder.scheduledtask.compat;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Performs the read-only, process-start V037 schema probe. */
public final class V037SchemaCapabilityDetector {

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final String COLUMN_QUERY = """
            SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
                   EXTRA, GENERATION_EXPRESSION
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = ?
               AND TABLE_NAME IN ('scheduled_task','scheduled_task_run','dispatch','artifact',
                                  'workitem_comment','workitem_comment_mention',
                                  'workitem_comment_delivery','workitem','org')
            """;
    private static final String INDEX_QUERY = """
            SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE
              FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = ?
               AND TABLE_NAME IN ('scheduled_task','scheduled_task_run','dispatch','artifact',
                                  'workitem_comment','workitem_comment_mention',
                                  'workitem_comment_delivery','workitem','org')
             ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

    private static final Map<ColumnKey, ColumnContract> SHARED_COLUMNS = sharedColumns();

    private static final Map<ColumnKey, ColumnContract> SCHEDULED_COLUMNS = scheduledColumns();
    private static final Map<IndexKey, IndexContract> SCHEDULED_INDEXES = scheduledIndexes();

    private final V037SchemaCapabilityClassifier classifier;

    public V037SchemaCapabilityDetector(V037SchemaCapabilityClassifier classifier) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    public V037SchemaCapability detect(DataSource dataSource) {
        return classifier.classify(inventory(dataSource));
    }

    public V037SchemaInventory inventory(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                throw new SQLException("current catalog is unavailable");
            }
            Map<ColumnKey, ColumnDetails> columns = readColumns(connection, catalog);
            Map<IndexKey, IndexDetails> indexes = readIndexes(connection, catalog);
            return classifyInventory(connection, columns, indexes);
        } catch (SQLException exception) {
            return V037SchemaInventory.failed("V037 schema capability probe failed");
        }
    }

    private Map<ColumnKey, ColumnDetails> readColumns(Connection connection, String catalog)
            throws SQLException {
        Map<ColumnKey, ColumnDetails> columns = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_QUERY)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, catalog);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ColumnKey key = key(resultSet.getString("TABLE_NAME"),
                            resultSet.getString("COLUMN_NAME"));
                    ColumnDetails previous = columns.put(key, new ColumnDetails(
                            normalizeType(resultSet.getString("COLUMN_TYPE")),
                            "YES".equalsIgnoreCase(resultSet.getString("IS_NULLABLE")),
                            resultSet.getString("COLUMN_DEFAULT"),
                            resultSet.getString("EXTRA"),
                            resultSet.getString("GENERATION_EXPRESSION")));
                    if (previous != null) {
                        throw new SQLException("duplicate column metadata");
                    }
                }
            }
        }
        return columns;
    }

    private Map<IndexKey, IndexDetails> readIndexes(Connection connection, String catalog)
            throws SQLException {
        Map<IndexKey, MutableIndex> collected = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(INDEX_QUERY)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, catalog);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    IndexKey key = new IndexKey(lower(resultSet.getString("TABLE_NAME")),
                            lower(resultSet.getString("INDEX_NAME")));
                    boolean unique = resultSet.getInt("NON_UNIQUE") == 0;
                    MutableIndex index = collected.computeIfAbsent(key,
                            ignored -> new MutableIndex(unique));
                    int sequence = resultSet.getInt("SEQ_IN_INDEX");
                    if (index.unique != unique || sequence != index.columns.size() + 1) {
                        throw new SQLException("incoherent index metadata");
                    }
                    index.columns.add(lower(resultSet.getString("COLUMN_NAME")));
                }
            }
        }
        Map<IndexKey, IndexDetails> indexes = new HashMap<>();
        collected.forEach((key, value) -> indexes.put(key,
                new IndexDetails(value.unique, List.copyOf(value.columns))));
        return indexes;
    }

    private V037SchemaInventory classifyInventory(
            Connection connection,
            Map<ColumnKey, ColumnDetails> columns,
            Map<IndexKey, IndexDetails> indexes) throws SQLException {
        Set<String> missingShared = new LinkedHashSet<>();
        for (Map.Entry<ColumnKey, ColumnContract> required : SHARED_COLUMNS.entrySet()) {
            ColumnDetails actual = columns.get(required.getKey());
            if (actual == null) {
                missingShared.add(required.getKey().externalName());
            } else if (!required.getValue().matches(actual)) {
                throw new SQLException("incompatible shared V037 column");
            }
        }
        boolean sourceAwareReady = missingShared.isEmpty();

        Set<String> missingScheduled = new LinkedHashSet<>();
        SCHEDULED_COLUMNS.forEach((key, contract) -> {
            ColumnDetails actual = columns.get(key);
            if (actual == null || !contract.matches(actual)) {
                missingScheduled.add(key.externalName());
            }
        });
        SCHEDULED_INDEXES.forEach((key, contract) -> {
            IndexDetails actual = indexes.get(key);
            if (actual == null || !contract.matches(actual)) {
                missingScheduled.add(key.externalName());
            }
        });
        requireExtra(columns, missingScheduled, "scheduled_task", "id", "auto_increment");
        requireExtra(columns, missingScheduled, "scheduled_task_run", "id", "auto_increment");
        requireExtra(columns, missingScheduled, "scheduled_task", "gmt_modified",
                "on update current_timestamp(3)");
        requireExtra(columns, missingScheduled, "scheduled_task_run", "gmt_modified",
                "on update current_timestamp(3)");
        ColumnDetails generated = columns.get(key("dispatch", "normalized_idempotency_key"));
        if (generated == null || !isIntendedGeneratedColumn(generated)) {
            missingScheduled.add("dispatch.normalized_idempotency_key");
        }

        Set<String> missing = new LinkedHashSet<>(missingShared);
        missing.addAll(missingScheduled);
        boolean anyV037Object = columns.keySet().stream().anyMatch(SHARED_COLUMNS::containsKey)
                || columns.keySet().stream().anyMatch(key ->
                        key.table.equals("scheduled_task") || key.table.equals("scheduled_task_run"))
                || columns.containsKey(key("dispatch", "normalized_idempotency_key"))
                || indexes.keySet().stream().anyMatch(SCHEDULED_INDEXES::containsKey);
        Set<String> tables = new LinkedHashSet<>();
        columns.keySet().forEach(key -> tables.add(key.table));
        boolean scheduledDataExists = findScheduledData(
                connection, columns, indexes, tables, sourceAwareReady);
        return new V037SchemaInventory(true, anyV037Object, sourceAwareReady,
                missingScheduled.isEmpty(), scheduledDataExists, missing, null, Instant.now());
    }

    private boolean findScheduledData(Connection connection,
                                      Map<ColumnKey, ColumnDetails> columns,
                                      Map<IndexKey, IndexDetails> indexes,
                                      Set<String> tables,
                                      boolean sourceAwareReady) throws SQLException {
        List<String> queries = new ArrayList<>();
        if (tables.contains("scheduled_task")) {
            queries.add("SELECT 1 FROM scheduled_task LIMIT 1");
        }
        if (tables.contains("scheduled_task_run")) {
            queries.add("SELECT 1 FROM scheduled_task_run LIMIT 1");
        }
        if (!queries.isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (String query : queries) {
                    try (ResultSet resultSet = statement.executeQuery(query)) {
                        if (resultSet.next()) {
                            return true;
                        }
                    }
                }
            }
        }
        if (!sourceAwareReady) {
            validateEvidenceIndexes(columns, indexes);
        }
        if (!hasBusinessEvidenceColumn(columns)) {
            return false;
        }
        if (sourceAwareReady) {
            return false;
        }
        return existsForAnyWorkspace(connection,
                columns, "dispatch", "source_type", "idx_dispatch_source",
                "SCHEDULED_TASK_RUN")
                || existsForAnyWorkspace(connection,
                columns, "artifact", "source_type", "idx_artifact_source",
                "SCHEDULED_TASK", "SCHEDULED_TASK_RUN")
                || existsForAnyWorkspace(connection,
                columns, "workitem_comment", "source_type", "idx_comment_source",
                "SCHEDULED_TASK_RUN")
                || existsForAnyWorkspace(connection,
                columns, "workitem_comment_mention", "source_type", "idx_mention_source",
                "SCHEDULED_TASK_RUN")
                || existsForAnyWorkspace(connection,
                columns, "workitem_comment_delivery", "source_type", "idx_delivery_source",
                "SCHEDULED_TASK_RUN")
                || existsForAnyWorkspace(connection,
                columns, "workitem", "origin_type", "idx_workitem_origin",
                "SCHEDULED_TASK_RUN");
    }

    private void validateEvidenceIndexes(Map<ColumnKey, ColumnDetails> columns,
                                         Map<IndexKey, IndexDetails> indexes) throws SQLException {
        requireEvidenceIndex(columns, indexes, "dispatch", "source_type", "idx_dispatch_source");
        requireEvidenceIndex(columns, indexes, "artifact", "source_type", "idx_artifact_source");
        requireEvidenceIndex(columns, indexes, "workitem_comment", "source_type", "idx_comment_source");
        requireEvidenceIndex(columns, indexes, "workitem_comment_mention", "source_type", "idx_mention_source");
        requireEvidenceIndex(columns, indexes, "workitem_comment_delivery", "source_type", "idx_delivery_source");
        requireEvidenceIndex(columns, indexes, "workitem", "origin_type", "idx_workitem_origin");
        if (hasBusinessEvidenceColumn(columns)) {
            IndexDetails workspacePrimary = indexes.get(new IndexKey("org", "primary"));
            if (!columns.containsKey(key("org", "id")) || workspacePrimary == null
                    || !new IndexContract(true, List.of("id")).matches(workspacePrimary)) {
                throw new SQLException("workspace inventory primary key unavailable");
            }
        }
    }

    private void requireEvidenceIndex(Map<ColumnKey, ColumnDetails> columns,
                                      Map<IndexKey, IndexDetails> indexes,
                                      String table, String column, String index) throws SQLException {
        if (!columns.containsKey(key(table, column))) {
            return;
        }
        IndexKey indexKey = new IndexKey(table, index);
        IndexContract expected = SCHEDULED_INDEXES.get(indexKey);
        IndexDetails actual = indexes.get(indexKey);
        if (expected == null || actual == null || !expected.matches(actual)) {
            throw new SQLException("V037 evidence index unavailable");
        }
    }

    private boolean hasBusinessEvidenceColumn(Map<ColumnKey, ColumnDetails> columns) {
        return columns.containsKey(key("dispatch", "source_type"))
                || columns.containsKey(key("artifact", "source_type"))
                || columns.containsKey(key("workitem_comment", "source_type"))
                || columns.containsKey(key("workitem_comment_mention", "source_type"))
                || columns.containsKey(key("workitem_comment_delivery", "source_type"))
                || columns.containsKey(key("workitem", "origin_type"));
    }

    private boolean existsForAnyWorkspace(Connection connection,
                                       Map<ColumnKey, ColumnDetails> columns,
                                       String table, String typeColumn, String index,
                                       String... types) throws SQLException {
        if (!columns.containsKey(key(table, typeColumn))) {
            return false;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(types.length, "?"));
        String predicate = types.length == 1
                ? "b." + typeColumn + " = ?"
                : "b." + typeColumn + " IN (" + placeholders + ")";
        String sql = "SELECT 1 FROM org w FORCE INDEX (PRIMARY) STRAIGHT_JOIN "
                + table + " b FORCE INDEX (" + index + ")"
                + " ON b.tenant_id = w.id AND " + predicate + " LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            for (int i = 0; i < types.length; i++) {
                statement.setString(i + 1, types[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean isIntendedGeneratedColumn(ColumnDetails column) {
        if (!"varchar(137)".equals(column.type)
                || !column.nullable
                || column.defaultValue != null
                || column.extra == null
                || !"stored generated".equals(column.extra.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        String expression = compactExpression(column.generationExpression);
        String mysqlCanonical = "casewhensource_type='workitem'andregexp_likeidempotency_key,'^[0-9]+:[0-9]+:[0-9]+$'thenconcat'workitem:',idempotency_keyelseidempotency_keyend";
        String operatorCanonical = "casewhensource_type='workitem'andidempotency_keyregexp'^[0-9]+:[0-9]+:[0-9]+$'thenconcat'workitem:',idempotency_keyelseidempotency_keyend";
        return expression.equals(mysqlCanonical) || expression.equals(operatorCanonical);
    }

    private static String compactExpression(String expression) {
        if (expression == null) {
            return "";
        }
        return expression.toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replace("\\", "")
                .replace("_utf8mb4", "")
                .replace("(", "")
                .replace(")", "")
                .replaceAll("\\s+", "");
    }

    private static Map<ColumnKey, ColumnContract> scheduledColumns() {
        Map<ColumnKey, ColumnContract> contracts = new LinkedHashMap<>();
        required(contracts, "scheduled_task", "id", "bigint unsigned", null);
        required(contracts, "scheduled_task", "workspace_id", "bigint unsigned", null);
        required(contracts, "scheduled_task", "name", "varchar(256)", null);
        required(contracts, "scheduled_task", "instruction_md", "mediumtext", null);
        required(contracts, "scheduled_task", "squad_id", "bigint unsigned", null);
        required(contracts, "scheduled_task", "initial_agent_id", "bigint unsigned", null);
        required(contracts, "scheduled_task", "schedule_type", "varchar(16)", null);
        nullable(contracts, "scheduled_task", "run_at", "datetime(3)");
        nullable(contracts, "scheduled_task", "cron_expression", "varchar(128)");
        required(contracts, "scheduled_task", "timezone", "varchar(64)", null);
        required(contracts, "scheduled_task", "session_mode", "varchar(16)", "ISOLATED");
        required(contracts, "scheduled_task", "overlap_policy", "varchar(16)", "SKIP");
        required(contracts, "scheduled_task", "misfire_policy", "varchar(16)", "FIRE_LATEST");
        required(contracts, "scheduled_task", "start_deadline_seconds", "int", "21600");
        required(contracts, "scheduled_task", "affinity_timeout_seconds", "int", "1800");
        required(contracts, "scheduled_task", "status", "varchar(16)", "ACTIVE");
        nullable(contracts, "scheduled_task", "next_fire_at", "datetime(3)");
        nullable(contracts, "scheduled_task", "last_fire_at", "datetime(3)");
        required(contracts, "scheduled_task", "gmt_create", "datetime(3)", "CURRENT_TIMESTAMP(3)");
        required(contracts, "scheduled_task", "gmt_modified", "datetime(3)", "CURRENT_TIMESTAMP(3)");
        required(contracts, "scheduled_task", "creator_id", "bigint unsigned", null);
        nullable(contracts, "scheduled_task", "modifier_id", "bigint unsigned");
        required(contracts, "scheduled_task", "is_deleted", "tinyint", "0");
        required(contracts, "scheduled_task", "version", "int", "0");

        required(contracts, "scheduled_task_run", "id", "bigint unsigned", null);
        required(contracts, "scheduled_task_run", "workspace_id", "bigint unsigned", null);
        required(contracts, "scheduled_task_run", "scheduled_task_id", "bigint unsigned", null);
        required(contracts, "scheduled_task_run", "trigger_key", "varchar(256)", null);
        required(contracts, "scheduled_task_run", "trigger_type", "varchar(16)", null);
        required(contracts, "scheduled_task_run", "scheduled_at", "datetime(3)", null);
        nullable(contracts, "scheduled_task_run", "started_at", "datetime(3)");
        nullable(contracts, "scheduled_task_run", "finished_at", "datetime(3)");
        required(contracts, "scheduled_task_run", "status", "varchar(32)", null);
        nullable(contracts, "scheduled_task_run", "skip_reason", "varchar(32)");
        required(contracts, "scheduled_task_run", "squad_id", "bigint unsigned", null);
        required(contracts, "scheduled_task_run", "initial_agent_id", "bigint unsigned", null);
        nullable(contracts, "scheduled_task_run", "current_agent_id", "bigint unsigned");
        nullable(contracts, "scheduled_task_run", "sdlc_id", "bigint unsigned");
        nullable(contracts, "scheduled_task_run", "current_step_id", "bigint unsigned");
        required(contracts, "scheduled_task_run", "session_mode", "varchar(16)", null);
        nullable(contracts, "scheduled_task_run", "resume_from_run_id", "bigint unsigned");
        required(contracts, "scheduled_task_run", "degraded_resume", "tinyint", "0");
        nullable(contracts, "scheduled_task_run", "degraded_reason", "varchar(512)");
        required(contracts, "scheduled_task_run", "execution_snapshot_json", "json", null);
        nullable(contracts, "scheduled_task_run", "result_summary", "mediumtext");
        nullable(contracts, "scheduled_task_run", "error", "varchar(1024)");
        required(contracts, "scheduled_task_run", "owner_id", "bigint unsigned", null);
        required(contracts, "scheduled_task_run", "gmt_create", "datetime(3)", "CURRENT_TIMESTAMP(3)");
        required(contracts, "scheduled_task_run", "gmt_modified", "datetime(3)", "CURRENT_TIMESTAMP(3)");
        required(contracts, "scheduled_task_run", "creator_id", "bigint unsigned", null);
        nullable(contracts, "scheduled_task_run", "modifier_id", "bigint unsigned");
        required(contracts, "scheduled_task_run", "version", "int", "0");
        return Map.copyOf(contracts);
    }

    private static Map<ColumnKey, ColumnContract> sharedColumns() {
        Map<ColumnKey, ColumnContract> contracts = new LinkedHashMap<>();
        required(contracts, "dispatch", "source_type", "varchar(32)", "WORKITEM");
        required(contracts, "artifact", "source_type", "varchar(32)", "WORKITEM");
        required(contracts, "workitem_comment", "source_type", "varchar(32)", "WORKITEM");
        required(contracts, "workitem_comment_mention", "source_type", "varchar(32)", "WORKITEM");
        required(contracts, "workitem_comment_delivery", "source_type", "varchar(32)", "WORKITEM");
        nullable(contracts, "workitem", "origin_type", "varchar(32)");
        nullable(contracts, "workitem", "origin_id", "bigint unsigned");
        return Map.copyOf(contracts);
    }

    private static Map<IndexKey, IndexContract> scheduledIndexes() {
        Map<IndexKey, IndexContract> contracts = new LinkedHashMap<>();
        index(contracts, "dispatch", "uk_dispatch_normalized_idempotency", true,
                "tenant_id", "normalized_idempotency_key");
        index(contracts, "dispatch", "idx_dispatch_source", false,
                "tenant_id", "source_type", "workitem_id", "id");
        index(contracts, "artifact", "idx_artifact_source", false,
                "tenant_id", "source_type", "workitem_id", "id");
        index(contracts, "workitem_comment", "idx_comment_source", false,
                "tenant_id", "source_type", "workitem_id", "id");
        index(contracts, "workitem_comment_mention", "idx_mention_source", false,
                "tenant_id", "source_type", "workitem_id", "id");
        index(contracts, "workitem_comment_delivery", "idx_delivery_source", false,
                "tenant_id", "source_type", "workitem_id", "id");
        index(contracts, "workitem", "idx_workitem_origin", false,
                "tenant_id", "origin_type", "origin_id");
        index(contracts, "scheduled_task", "idx_scheduled_task_due", false,
                "status", "is_deleted", "next_fire_at", "id");
        index(contracts, "scheduled_task", "idx_scheduled_task_owner", false,
                "workspace_id", "creator_id", "status", "id");
        index(contracts, "scheduled_task", "primary", true, "id");
        index(contracts, "scheduled_task_run", "uk_scheduled_task_trigger", true,
                "workspace_id", "trigger_key");
        index(contracts, "scheduled_task_run", "idx_scheduled_task_run_task", false,
                "workspace_id", "scheduled_task_id", "id");
        index(contracts, "scheduled_task_run", "idx_scheduled_task_run_status", false,
                "workspace_id", "status", "scheduled_at", "id");
        index(contracts, "scheduled_task_run", "idx_scheduled_task_run_recovery", false,
                "status", "gmt_modified", "id");
        index(contracts, "scheduled_task_run", "idx_scheduled_task_run_resume", false,
                "workspace_id", "resume_from_run_id");
        index(contracts, "scheduled_task_run", "idx_scheduled_task_run_queue", false,
                "workspace_id", "scheduled_task_id", "status", "scheduled_at", "id");
        index(contracts, "scheduled_task_run", "idx_scheduled_task_run_health", false,
                "workspace_id", "scheduled_task_id", "finished_at", "status");
        index(contracts, "scheduled_task_run", "primary", true, "id");
        return Map.copyOf(contracts);
    }

    private static void requireExtra(Map<ColumnKey, ColumnDetails> columns,
                                     Set<String> missingObjects,
                                     String table, String column, String requiredFragment) {
        ColumnDetails actual = columns.get(key(table, column));
        if (actual == null || actual.extra == null
                || !actual.extra.toLowerCase(Locale.ROOT).contains(requiredFragment)) {
            missingObjects.add(table + "." + column);
        }
    }

    private static void required(Map<ColumnKey, ColumnContract> contracts, String table,
                                 String column, String type, String defaultValue) {
        contracts.put(key(table, column), new ColumnContract(type, false, defaultValue));
    }

    private static void nullable(Map<ColumnKey, ColumnContract> contracts, String table,
                                 String column, String type) {
        contracts.put(key(table, column), new ColumnContract(type, true, null));
    }

    private static void index(Map<IndexKey, IndexContract> contracts, String table,
                              String name, boolean unique, String... columns) {
        contracts.put(new IndexKey(table, name),
                new IndexContract(unique, List.of(columns)));
    }

    private static ColumnKey key(String table, String column) {
        return new ColumnKey(lower(table), lower(column));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record ColumnKey(String table, String column) {
        private String externalName() { return table + "." + column; }
    }

    private record ColumnDetails(String type, boolean nullable, String defaultValue,
                                 String extra, String generationExpression) {}

    private record ColumnContract(String type, boolean nullable, String defaultValue) {
        private boolean matches(ColumnDetails actual) {
            return type.equals(actual.type)
                    && nullable == actual.nullable
                    && normalizedDefault(defaultValue).equals(normalizedDefault(actual.defaultValue));
        }

        private static String normalizedDefault(String value) {
            if (value == null) {
                return "<null>";
            }
            String normalized = value.trim();
            if (normalized.length() >= 2 && normalized.startsWith("'") && normalized.endsWith("'")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            return normalized.toUpperCase(Locale.ROOT);
        }
    }

    private record IndexKey(String table, String index) {
        private String externalName() { return table + "." + index; }
    }

    private record IndexDetails(boolean unique, List<String> columns) {}

    private record IndexContract(boolean unique, List<String> columns) {
        private boolean matches(IndexDetails actual) {
            return unique == actual.unique && columns.equals(actual.columns);
        }
    }

    private static final class MutableIndex {
        private final boolean unique;
        private final List<String> columns = new ArrayList<>();

        private MutableIndex(boolean unique) {
            this.unique = unique;
        }
    }
}
