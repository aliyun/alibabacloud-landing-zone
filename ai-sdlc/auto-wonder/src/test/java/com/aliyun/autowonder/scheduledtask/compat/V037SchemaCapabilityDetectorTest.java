package com.aliyun.autowonder.scheduledtask.compat;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V037SchemaCapabilityDetectorTest {

    @Test
    void connectionFailureIsInconsistentAndNeverFallsBackToLegacy() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("metadata denied"));

        V037SchemaCapability capability = detector().detect(dataSource);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
    }

    @Test
    void metadataQueryFailureIsInconsistentAndNeverFallsBackToLegacy() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("autowonder");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("information_schema denied"));

        V037SchemaCapability capability = detector().detect(dataSource);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
    }

    @Test
    void inventoriesOnlyTheExactSharedFieldsBeforeSelectingSourceAwareMapper() throws Exception {
        List<ColumnRow> columns = sharedColumns();
        columns.removeIf(row -> row.table.equals("workitem_comment_delivery"));

        V037SchemaInventory inventory = detector().inventory(dataSource(columns, readyIndexes(), false));
        V037SchemaCapability capability = new V037SchemaCapabilityClassifier().classify(inventory);

        assertFalse(inventory.sourceAwareColumnsReady());
        assertTrue(inventory.missingObjects().contains("workitem_comment_delivery.source_type"));
        assertFalse(inventory.missingObjects().contains("dispatch.source_type"));
        assertFalse(inventory.missingObjects().contains("artifact.source_type"));
        assertFalse(inventory.missingObjects().contains("workitem_comment.source_type"));
        assertFalse(inventory.missingObjects().contains("workitem_comment_mention.source_type"));
        assertFalse(inventory.missingObjects().contains("workitem.origin_type"));
        assertFalse(inventory.missingObjects().contains("workitem.origin_id"));
        assertEquals(V037SchemaMode.V037_PARTIAL, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
    }

    @Test
    void classifiesCompleteCanonicalSchemaAsReady() throws Exception {
        V037SchemaCapability capability = detector().detect(
                dataSource(readyColumns(), readyIndexes(), false));

        assertEquals(V037SchemaMode.V037_READY, capability.mode(), capability.missingObjects().toString());
        assertEquals(V037MapperMode.SOURCE_AWARE, capability.mapperMode());
        assertTrue(capability.scheduledAvailable());
        assertTrue(capability.missingObjects().isEmpty());
    }

    @Test
    void rejectsWrongDefaultAndWrongExactIndexOrderAsIncomplete() throws Exception {
        List<ColumnRow> columns = readyColumns();
        replace(columns, "scheduled_task", "overlap_policy",
                column("scheduled_task", "overlap_policy", "varchar(16)", false, "ALLOW", "", ""));
        List<IndexRow> indexes = readyIndexes();
        indexes.removeIf(row -> row.index.equals("idx_scheduled_task_run_queue"));
        indexes.addAll(index("scheduled_task_run", "idx_scheduled_task_run_queue", false,
                "workspace_id", "status", "scheduled_task_id", "scheduled_at", "id"));

        V037SchemaInventory inventory = detector().inventory(dataSource(columns, indexes, false));

        assertFalse(inventory.scheduledObjectsReady());
        assertTrue(inventory.missingObjects().contains("scheduled_task.overlap_policy"));
        assertTrue(inventory.missingObjects().contains(
                "scheduled_task_run.idx_scheduled_task_run_queue"));
    }

    @Test
    void incompatibleSharedColumnDefinitionsFailProbeClosed() throws Exception {
        List<ColumnRow> wrongType = readyColumns();
        replace(wrongType, "artifact", "source_type",
                column("artifact", "source_type", "varchar(64)", false, "WORKITEM", "", ""));
        List<ColumnRow> wrongDefault = readyColumns();
        replace(wrongDefault, "artifact", "source_type",
                column("artifact", "source_type", "varchar(32)", false, "OTHER", "", ""));
        List<ColumnRow> wrongNullability = readyColumns();
        replace(wrongNullability, "artifact", "source_type",
                column("artifact", "source_type", "varchar(32)", true, "WORKITEM", "", ""));

        for (List<ColumnRow> columns : List.of(wrongType, wrongDefault, wrongNullability)) {
            assertEquals(V037SchemaMode.INCONSISTENT,
                    detector().detect(dataSource(columns, readyIndexes(), false)).mode());
        }
    }

    @Test
    void scheduledTablesRequireExactPrimaryKeyAutoIncrementAndOnUpdate() throws Exception {
        List<IndexRow> missingPrimary = readyIndexes();
        missingPrimary.removeIf(row -> row.table.equals("scheduled_task")
                && row.index.equalsIgnoreCase("PRIMARY"));
        assertFalse(detector().detect(dataSource(readyColumns(), missingPrimary, false))
                .scheduledAvailable());

        List<ColumnRow> missingAutoIncrement = readyColumns();
        replace(missingAutoIncrement, "scheduled_task", "id",
                column("scheduled_task", "id", "bigint unsigned", false, null, "", ""));
        assertFalse(detector().detect(dataSource(missingAutoIncrement, readyIndexes(), false))
                .scheduledAvailable());

        List<ColumnRow> missingOnUpdate = readyColumns();
        replace(missingOnUpdate, "scheduled_task_run", "gmt_modified",
                column("scheduled_task_run", "gmt_modified", "datetime(3)", false,
                        "CURRENT_TIMESTAMP(3)", "DEFAULT_GENERATED", ""));
        assertFalse(detector().detect(dataSource(missingOnUpdate, readyIndexes(), false))
                .scheduledAvailable());
    }

    @Test
    void malformedExistingScheduledTableStillRunsBoundedEvidenceProbe() throws Exception {
        List<ColumnRow> columns = sharedColumns();
        columns.add(column("scheduled_task", "workspace_id", "bigint unsigned", false,
                null, "", ""));
        ProbeFixture fixture = fixture(columns, readyIndexes(), List.of(7L), call ->
                normalize(call.sql).equals("SELECT 1 FROM scheduled_task LIMIT 1"));

        V037SchemaInventory inventory = detector().inventory(fixture.dataSource);

        assertTrue(inventory.scheduledDataExists());
        assertTrue(fixture.calls.stream().anyMatch(call ->
                normalize(call.sql).equals("SELECT 1 FROM scheduled_task LIMIT 1")));
    }

    @Test
    void generatedColumnRequiresCompletePhysicalContract() throws Exception {
        List<ColumnRow> columns = readyColumns();
        ColumnRow canonical = columns.stream().filter(row -> row.table.equals("dispatch")
                && row.column.equals("normalized_idempotency_key")).findFirst().orElseThrow();
        replace(columns, "dispatch", "normalized_idempotency_key",
                column(canonical.table, canonical.column, canonical.type, false,
                        "unexpected", canonical.extra, canonical.expression));

        V037SchemaCapability capability = detector().detect(
                dataSource(columns, readyIndexes(), false));

        assertFalse(capability.scheduledAvailable());
        assertTrue(capability.missingObjects().contains("dispatch.normalized_idempotency_key"));
    }

    @Test
    void scheduledEvidenceIsReportedOnlyWhenSchemaObjectsExist() throws Exception {
        ProbeFixture fixture = fixture(readyColumns(), readyIndexes(), List.of(7L),
                call -> call.sql.contains("FROM scheduled_task "));

        V037SchemaInventory inventory = detector().inventory(fixture.dataSource);

        assertTrue(inventory.scheduledDataExists());
        assertTrue(fixture.calls.stream().anyMatch(call ->
                normalize(call.sql).equals("SELECT 1 FROM scheduled_task LIMIT 1")));
        assertFalse(fixture.calls.stream().anyMatch(call ->
                call.sql.contains("FROM scheduled_task WHERE")));
    }

    @Test
    void allBusinessEvidenceUsesFixedTenantFirstIndexedQueriesAcrossEveryTenant() throws Exception {
        List<ColumnRow> columns = readyColumns();
        columns.removeIf(row -> row.table.equals("scheduled_task")
                || row.table.equals("scheduled_task_run")
                || row.table.equals("workitem") && row.column.equals("origin_id"));
        List<Long> manyTenants = java.util.stream.LongStream.rangeClosed(1, 100)
                .boxed().toList();
        ProbeFixture fixture = fixture(columns, readyIndexes(), manyTenants, call -> false);

        V037SchemaInventory inventory = detector().inventory(fixture.dataSource);

        assertFalse(inventory.scheduledDataExists());
        List<QueryCall> business = fixture.calls.stream()
                .filter(call -> Set.of("dispatch", "artifact", "workitem_comment",
                                "workitem_comment_mention", "workitem_comment_delivery", "workitem")
                        .stream().anyMatch(table -> call.sql.contains("JOIN " + table + " ")))
                .toList();
        assertEquals(6, business.size());
        assertBusinessProbe(business, "dispatch", "idx_dispatch_source", Map.of(
                1, "SCHEDULED_TASK_RUN"));
        assertBusinessProbe(business, "artifact", "idx_artifact_source", Map.of(
                1, "SCHEDULED_TASK", 2, "SCHEDULED_TASK_RUN"));
        assertBusinessProbe(business, "workitem_comment", "idx_comment_source", Map.of(
                1, "SCHEDULED_TASK_RUN"));
        assertBusinessProbe(business, "workitem_comment_mention", "idx_mention_source", Map.of(
                1, "SCHEDULED_TASK_RUN"));
        assertBusinessProbe(business, "workitem_comment_delivery", "idx_delivery_source", Map.of(
                1, "SCHEDULED_TASK_RUN"));
        assertBusinessProbe(business, "workitem", "idx_workitem_origin", Map.of(
                1, "SCHEDULED_TASK_RUN"));
        assertEquals(6, business.size(), "business roundtrips must not grow with tenant count");
    }

    @Test
    void appliesTimeoutToEveryMetadataAndEvidenceStatement() throws Exception {
        List<ColumnRow> columns = readyColumns();
        columns.removeIf(row -> row.table.equals("scheduled_task")
                || row.table.equals("scheduled_task_run")
                || row.table.equals("workitem") && row.column.equals("origin_id"));
        ProbeFixture fixture = fixture(columns, readyIndexes(), List.of(7L), call -> false);

        detector().inventory(fixture.dataSource);

        assertEquals(8, fixture.queryTimeouts.size());
        assertTrue(fixture.queryTimeouts.stream().allMatch(timeout -> timeout == 5));

        ProbeFixture scheduledFixture = fixture(
                readyColumns(), readyIndexes(), List.of(7L), call -> false);
        detector().inventory(scheduledFixture.dataSource);
        assertEquals(3, scheduledFixture.queryTimeouts.size());
        assertTrue(scheduledFixture.queryTimeouts.stream().allMatch(timeout -> timeout == 5));
    }

    @Test
    void evidenceTimeoutFailsWholeProbeClosed() throws Exception {
        List<ColumnRow> columns = readyColumns();
        columns.removeIf(row -> row.table.equals("scheduled_task")
                || row.table.equals("scheduled_task_run")
                || row.table.equals("workitem") && row.column.equals("origin_id"));
        ProbeFixture fixture = fixture(columns, readyIndexes(), List.of(7L), call -> {
            if (call.sql.contains("JOIN dispatch ")) {
                throw new SQLTimeoutException("evidence timed out");
            }
            return false;
        });

        V037SchemaCapability capability = detector().detect(fixture.dataSource);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
    }

    @Test
    void missingExactSourceIndexFailsClosedWithoutScanningBusinessTable() throws Exception {
        List<ColumnRow> columns = new ArrayList<>(List.of(
                column("org", "id", "bigint unsigned", false, null, "", ""),
                column("dispatch", "source_type", "varchar(32)", false, "WORKITEM", "", "")));
        ProbeFixture fixture = fixture(columns, List.of(), List.of(7L), call -> false);

        V037SchemaCapability capability = detector().detect(fixture.dataSource);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertFalse(fixture.calls.stream().anyMatch(call -> call.sql.contains("FROM dispatch ")));
    }

    @Test
    void sourceAwarePartialSchemaDoesNotFailClosedForMissingSourceIndex() throws Exception {
        List<IndexRow> indexes = readyIndexes();
        indexes.removeIf(row -> row.index.equals("idx_artifact_source"));
        ProbeFixture fixture = fixture(readyColumns(), indexes, List.of(7L), call -> false);

        V037SchemaCapability capability = detector().detect(fixture.dataSource);

        assertEquals(V037SchemaMode.V037_PARTIAL, capability.mode());
        assertEquals(V037MapperMode.SOURCE_AWARE, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
        assertTrue(capability.missingObjects().contains("artifact.idx_artifact_source"));
        assertFalse(fixture.calls.stream().anyMatch(call -> call.sql.contains("FROM artifact ")));
    }

    @Test
    void evidenceSQLExceptionFailsWholeProbeClosed() throws Exception {
        List<ColumnRow> columns = readyColumns();
        columns.removeIf(row -> row.table.equals("scheduled_task")
                || row.table.equals("scheduled_task_run")
                || row.table.equals("workitem") && row.column.equals("origin_id"));
        ProbeFixture fixture = fixture(columns, readyIndexes(), List.of(7L), call -> {
            if (call.sql.contains("JOIN dispatch ")) {
                throw new SQLException("evidence denied");
            }
            return false;
        });

        V037SchemaCapability capability = detector().detect(fixture.dataSource);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
    }

    @Test
    void orgEnumerationSQLExceptionFailsWholeProbeClosed() throws Exception {
        List<ColumnRow> columns = readyColumns();
        columns.removeIf(row -> row.table.equals("scheduled_task")
                || row.table.equals("scheduled_task_run")
                || row.table.equals("workitem") && row.column.equals("origin_id"));
        ProbeFixture fixture = fixture(columns, readyIndexes(), List.of(7L), call -> {
            if (call.sql.contains("FROM org ")) {
                throw new SQLException("org enumeration denied");
            }
            return false;
        });

        V037SchemaCapability capability = detector().detect(fixture.dataSource);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
    }

    private V037SchemaCapabilityDetector detector() {
        return new V037SchemaCapabilityDetector(new V037SchemaCapabilityClassifier());
    }

    private DataSource dataSource(List<ColumnRow> columns, List<IndexRow> indexes,
                                  boolean evidence) throws Exception {
        return fixture(columns, indexes, List.of(7L), call -> evidence).dataSource;
    }

    private ProbeFixture fixture(List<ColumnRow> columns, List<IndexRow> indexes,
                                 List<Long> workspaceIds, EvidenceBehavior behavior) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        PreparedStatement indexStatement = mock(PreparedStatement.class);
        Statement evidenceStatement = mock(Statement.class);
        List<QueryCall> calls = new ArrayList<>();
        List<Integer> queryTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
            queryTimeouts.add(invocation.getArgument(0));
            return null;
        }).when(columnStatement).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
        doAnswer(invocation -> {
            queryTimeouts.add(invocation.getArgument(0));
            return null;
        }).when(indexStatement).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("autowonder");
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("information_schema.COLUMNS")) {
                return columnStatement;
            }
            if (sql.contains("information_schema.STATISTICS")) {
                return indexStatement;
            }
            PreparedStatement evidenceStatementForQuery = mock(PreparedStatement.class);
            QueryCall call = new QueryCall(sql, new LinkedHashMap<>());
            calls.add(call);
            doAnswer(timeout -> {
                queryTimeouts.add(timeout.getArgument(0));
                return null;
            }).when(evidenceStatementForQuery).setQueryTimeout(
                    org.mockito.ArgumentMatchers.anyInt());
            doAnswer(set -> {
                call.parameters.put(set.getArgument(0), set.getArgument(1));
                return null;
            }).when(evidenceStatementForQuery).setLong(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyLong());
            doAnswer(set -> {
                call.parameters.put(set.getArgument(0), set.getArgument(1));
                return null;
            }).when(evidenceStatementForQuery).setString(org.mockito.ArgumentMatchers.anyInt(),
                    anyString());
            when(evidenceStatementForQuery.executeQuery()).thenAnswer(ignored -> {
                boolean evidence = behavior.exists(call);
                return normalize(sql).startsWith("SELECT id FROM org ")
                        ? tenantResultSet(workspaceIds)
                        : evidenceResultSet(evidence);
            });
            return evidenceStatementForQuery;
        });
        ResultSet columnResultSet = columnResultSet(columns);
        ResultSet indexResultSet = indexResultSet(indexes);
        when(columnStatement.executeQuery()).thenReturn(columnResultSet);
        when(indexStatement.executeQuery()).thenReturn(indexResultSet);
        when(connection.createStatement()).thenReturn(evidenceStatement);
        doAnswer(timeout -> {
            queryTimeouts.add(timeout.getArgument(0));
            return null;
        }).when(evidenceStatement).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
        when(evidenceStatement.executeQuery(anyString())).thenAnswer(invocation -> {
            QueryCall call = new QueryCall(invocation.getArgument(0), new LinkedHashMap<>());
            calls.add(call);
            return evidenceResultSet(behavior.exists(call));
        });
        return new ProbeFixture(dataSource, calls, queryTimeouts);
    }

    private ResultSet columnResultSet(List<ColumnRow> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = {-1};
        when(rs.next()).thenAnswer(ignored -> ++cursor[0] < rows.size());
        when(rs.getString(anyString())).thenAnswer(invocation -> {
            ColumnRow row = rows.get(cursor[0]);
            return switch ((String) invocation.getArgument(0)) {
                case "TABLE_NAME" -> row.table;
                case "COLUMN_NAME" -> row.column;
                case "COLUMN_TYPE" -> row.type;
                case "IS_NULLABLE" -> row.nullable ? "YES" : "NO";
                case "COLUMN_DEFAULT" -> row.defaultValue;
                case "EXTRA" -> row.extra;
                case "GENERATION_EXPRESSION" -> row.expression;
                default -> throw new AssertionError(invocation.getArgument(0));
            };
        });
        return rs;
    }

    private ResultSet indexResultSet(List<IndexRow> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = {-1};
        when(rs.next()).thenAnswer(ignored -> ++cursor[0] < rows.size());
        when(rs.getString("TABLE_NAME")).thenAnswer(ignored -> rows.get(cursor[0]).table);
        when(rs.getString("INDEX_NAME")).thenAnswer(ignored -> rows.get(cursor[0]).index);
        when(rs.getString("COLUMN_NAME")).thenAnswer(ignored -> rows.get(cursor[0]).column);
        when(rs.getInt("SEQ_IN_INDEX")).thenAnswer(ignored -> rows.get(cursor[0]).sequence);
        when(rs.getInt("NON_UNIQUE")).thenAnswer(ignored -> rows.get(cursor[0]).unique ? 0 : 1);
        return rs;
    }

    private ResultSet evidenceResultSet(boolean evidence) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(evidence);
        return rs;
    }

    private ResultSet tenantResultSet(List<Long> workspaceIds) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = {-1};
        when(rs.next()).thenAnswer(ignored -> ++cursor[0] < workspaceIds.size());
        when(rs.getLong(1)).thenAnswer(ignored -> workspaceIds.get(cursor[0]));
        return rs;
    }

    private void assertBusinessProbe(List<QueryCall> calls, String table, String index,
                                     Map<Integer, Object> parameters) {
        QueryCall call = calls.stream().filter(candidate ->
                candidate.sql.contains("JOIN " + table + " ")).findFirst().orElseThrow();
        String sql = normalize(call.sql);
        assertTrue(sql.startsWith("SELECT 1 FROM org w FORCE INDEX (PRIMARY) STRAIGHT_JOIN "), sql);
        assertTrue(sql.contains("FORCE INDEX (" + index + ")"), sql);
        assertTrue(sql.contains(" ON "), sql);
        assertTrue(sql.contains(".tenant_id = w.id AND "), sql);
        assertTrue(sql.endsWith("LIMIT 1"), sql);
        assertEquals(parameters, call.parameters);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private List<ColumnRow> sharedColumns() {
        return new ArrayList<>(List.of(
                column("org", "id", "bigint unsigned", false, null, "", ""),
                column("dispatch", "source_type", "varchar(32)", false, "WORKITEM", "", ""),
                column("artifact", "source_type", "varchar(32)", false, "WORKITEM", "", ""),
                column("workitem_comment", "source_type", "varchar(32)", false, "WORKITEM", "", ""),
                column("workitem_comment_mention", "source_type", "varchar(32)", false, "WORKITEM", "", ""),
                column("workitem_comment_delivery", "source_type", "varchar(32)", false, "WORKITEM", "", ""),
                column("workitem", "origin_type", "varchar(32)", true, null, "", ""),
                column("workitem", "origin_id", "bigint unsigned", true, null, "", "")));
    }

    private List<ColumnRow> readyColumns() {
        List<ColumnRow> rows = sharedColumns();
        rows.add(column("dispatch", "normalized_idempotency_key", "varchar(137)", true, null,
                "STORED GENERATED", "case when ((`source_type` = _utf8mb4'WORKITEM') and regexp_like(`idempotency_key`,_utf8mb4'^[0-9]+:[0-9]+:[0-9]+$')) then concat(_utf8mb4'WORKITEM:',`idempotency_key`) else `idempotency_key` end"));
        addColumns(rows, "scheduled_task", Map.ofEntries(
                c("id", "bigint unsigned", null), c("workspace_id", "bigint unsigned", null),
                c("name", "varchar(256)", null), c("instruction_md", "mediumtext", null),
                c("squad_id", "bigint unsigned", null), c("initial_agent_id", "bigint unsigned", null),
                c("schedule_type", "varchar(16)", null), n("run_at", "datetime(3)"),
                n("cron_expression", "varchar(128)"), c("timezone", "varchar(64)", null),
                c("session_mode", "varchar(16)", "ISOLATED"), c("overlap_policy", "varchar(16)", "SKIP"),
                c("misfire_policy", "varchar(16)", "FIRE_LATEST"), c("start_deadline_seconds", "int", "21600"),
                c("affinity_timeout_seconds", "int", "1800"), c("status", "varchar(16)", "ACTIVE"),
                n("next_fire_at", "datetime(3)"), n("last_fire_at", "datetime(3)"),
                c("gmt_create", "datetime(3)", "CURRENT_TIMESTAMP(3)"),
                c("gmt_modified", "datetime(3)", "CURRENT_TIMESTAMP(3)"),
                c("creator_id", "bigint unsigned", null), n("modifier_id", "bigint unsigned"),
                c("is_deleted", "tinyint", "0"), c("version", "int", "0")));
        addColumns(rows, "scheduled_task_run", Map.ofEntries(
                c("id", "bigint unsigned", null), c("workspace_id", "bigint unsigned", null),
                c("scheduled_task_id", "bigint unsigned", null), c("trigger_key", "varchar(256)", null),
                c("trigger_type", "varchar(16)", null), c("scheduled_at", "datetime(3)", null),
                n("started_at", "datetime(3)"), n("finished_at", "datetime(3)"),
                c("status", "varchar(32)", null), n("skip_reason", "varchar(32)"),
                c("squad_id", "bigint unsigned", null), c("initial_agent_id", "bigint unsigned", null),
                n("current_agent_id", "bigint unsigned"), n("sdlc_id", "bigint unsigned"),
                n("current_step_id", "bigint unsigned"), c("session_mode", "varchar(16)", null),
                n("resume_from_run_id", "bigint unsigned"), c("degraded_resume", "tinyint", "0"),
                n("degraded_reason", "varchar(512)"), c("execution_snapshot_json", "json", null),
                n("result_summary", "mediumtext"), n("error", "varchar(1024)"),
                c("owner_id", "bigint unsigned", null), c("gmt_create", "datetime(3)", "CURRENT_TIMESTAMP(3)"),
                c("gmt_modified", "datetime(3)", "CURRENT_TIMESTAMP(3)"), c("creator_id", "bigint unsigned", null),
                n("modifier_id", "bigint unsigned"), c("version", "int", "0")));
        replace(rows, "scheduled_task", "id",
                column("scheduled_task", "id", "bigint unsigned", false,
                        null, "auto_increment", ""));
        replace(rows, "scheduled_task_run", "id",
                column("scheduled_task_run", "id", "bigint unsigned", false,
                        null, "auto_increment", ""));
        replace(rows, "scheduled_task", "gmt_modified",
                column("scheduled_task", "gmt_modified", "datetime(3)", false,
                        "CURRENT_TIMESTAMP(3)",
                        "DEFAULT_GENERATED on update CURRENT_TIMESTAMP(3)", ""));
        replace(rows, "scheduled_task_run", "gmt_modified",
                column("scheduled_task_run", "gmt_modified", "datetime(3)", false,
                        "CURRENT_TIMESTAMP(3)",
                        "DEFAULT_GENERATED on update CURRENT_TIMESTAMP(3)", ""));
        return rows;
    }

    private List<IndexRow> readyIndexes() {
        List<IndexRow> rows = new ArrayList<>();
        rows.addAll(index("org", "PRIMARY", true, "id"));
        rows.addAll(index("dispatch", "uk_dispatch_normalized_idempotency", true,
                "tenant_id", "normalized_idempotency_key"));
        rows.addAll(index("dispatch", "idx_dispatch_source", false,
                "tenant_id", "source_type", "workitem_id", "id"));
        rows.addAll(index("artifact", "idx_artifact_source", false,
                "tenant_id", "source_type", "workitem_id", "id"));
        rows.addAll(index("workitem_comment", "idx_comment_source", false,
                "tenant_id", "source_type", "workitem_id", "id"));
        rows.addAll(index("workitem_comment_mention", "idx_mention_source", false,
                "tenant_id", "source_type", "workitem_id", "id"));
        rows.addAll(index("workitem_comment_delivery", "idx_delivery_source", false,
                "tenant_id", "source_type", "workitem_id", "id"));
        rows.addAll(index("workitem", "idx_workitem_origin", false,
                "tenant_id", "origin_type", "origin_id"));
        rows.addAll(index("scheduled_task", "idx_scheduled_task_due", false,
                "status", "is_deleted", "next_fire_at", "id"));
        rows.addAll(index("scheduled_task", "idx_scheduled_task_owner", false,
                "workspace_id", "creator_id", "status", "id"));
        rows.addAll(index("scheduled_task", "PRIMARY", true, "id"));
        rows.addAll(index("scheduled_task_run", "uk_scheduled_task_trigger", true,
                "workspace_id", "trigger_key"));
        rows.addAll(index("scheduled_task_run", "idx_scheduled_task_run_task", false,
                "workspace_id", "scheduled_task_id", "id"));
        rows.addAll(index("scheduled_task_run", "idx_scheduled_task_run_status", false,
                "workspace_id", "status", "scheduled_at", "id"));
        rows.addAll(index("scheduled_task_run", "idx_scheduled_task_run_recovery", false,
                "status", "gmt_modified", "id"));
        rows.addAll(index("scheduled_task_run", "idx_scheduled_task_run_resume", false,
                "workspace_id", "resume_from_run_id"));
        rows.addAll(index("scheduled_task_run", "idx_scheduled_task_run_queue", false,
                "workspace_id", "scheduled_task_id", "status", "scheduled_at", "id"));
        rows.addAll(index("scheduled_task_run", "idx_scheduled_task_run_health", false,
                "workspace_id", "scheduled_task_id", "finished_at", "status"));
        rows.addAll(index("scheduled_task_run", "PRIMARY", true, "id"));
        return rows;
    }

    private void addColumns(List<ColumnRow> rows, String table, Map<String, ColumnSpec> columns) {
        columns.forEach((name, spec) -> rows.add(column(table, name, spec.type,
                spec.nullable, spec.defaultValue, "", "")));
    }

    private Map.Entry<String, ColumnSpec> c(String name, String type, String defaultValue) {
        return new AbstractMap.SimpleImmutableEntry<>(name,
                new ColumnSpec(type, false, defaultValue));
    }

    private Map.Entry<String, ColumnSpec> n(String name, String type) {
        return Map.entry(name, new ColumnSpec(type, true, null));
    }

    private ColumnRow column(String table, String column, String type, boolean nullable,
                             String defaultValue, String extra, String expression) {
        return new ColumnRow(table, column, type, nullable, defaultValue, extra, expression);
    }

    private List<IndexRow> index(String table, String name, boolean unique, String... columns) {
        List<IndexRow> rows = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            rows.add(new IndexRow(table, name, columns[i], i + 1, unique));
        }
        return rows;
    }

    private void replace(List<ColumnRow> rows, String table, String name, ColumnRow replacement) {
        rows.removeIf(row -> row.table.equals(table) && row.column.equals(name));
        rows.add(replacement);
    }

    private record ColumnSpec(String type, boolean nullable, String defaultValue) {}
    private record ColumnRow(String table, String column, String type, boolean nullable,
                             String defaultValue, String extra, String expression) {}
    private record IndexRow(String table, String index, String column, int sequence, boolean unique) {}
    private record ProbeFixture(DataSource dataSource, List<QueryCall> calls,
                                List<Integer> queryTimeouts) {}
    private record QueryCall(String sql, Map<Integer, Object> parameters) {}

    @FunctionalInterface
    private interface EvidenceBehavior {
        boolean exists(QueryCall call) throws SQLException;
    }
}
