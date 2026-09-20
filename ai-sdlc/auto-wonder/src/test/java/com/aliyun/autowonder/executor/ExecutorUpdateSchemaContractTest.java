package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the executor-upgrade storage. The canonical schema and the migration must stay
 * byte-identical where they overlap, otherwise a freshly created database and a migrated one drift
 * apart and the mappers only work on one of them.
 */
class ExecutorUpdateSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");
    private static final Path MIGRATION = Path.of("docs/migration/V064__executor_update_task.sql");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Path TASK_MAPPER = Path.of("src/main/resources/mapping/ExecutorUpdateTaskDao.xml");
    private static final Path EXECUTOR_MAPPER = Path.of("src/main/resources/mapping/ExecutorDao.xml");

    /**
     * The reported runtime version is presence state in Redis ({@code exec:version:{id}}, refreshed by
     * every heartbeat), so neither schema may grow a column for it: a column would be a second, staler
     * copy of the same fact, and the presence TTL is what makes "no version" mean "not judgeable".
     */
    @Test
    void theReportedVersionStaysInRedisAndNeverBecomesAColumn() throws Exception {
        String executorTable = createTable(Files.readString(CANONICAL_SCHEMA), "executor");
        assertFalse(executorTable.contains("last_reported_version"),
                "the canonical executor table must not carry the reported version");

        String migration = Files.readString(MIGRATION);
        assertFalse(migration.contains("last_reported_version"),
                "a migration column the canonical schema does not have would make the two drift apart");
        assertFalse(migration.contains("ALTER TABLE"),
                "this migration only creates the upgrade-task table; the executor table stays untouched");
        assertTrue(migration.contains("exec:version:{id}"),
                "the migration must record where the reported version actually lives");
    }

    @Test
    void migrationAndCanonicalSchemaDeclareTheSameUpgradeTaskTable() throws Exception {
        assertEquals(createTable(Files.readString(MIGRATION), "executor_update_task"),
                createTable(Files.readString(CANONICAL_SCHEMA), "executor_update_task"),
                "the executor_update_task definition must not drift between the two files");
    }

    @Test
    void upgradeTaskTableSeparatesSendingFromAwaitingTheClient() throws Exception {
        String table = createTable(Files.readString(CANONICAL_SCHEMA), "executor_update_task");

        assertTrue(table.contains("`delivered_at`    DATETIME(3)     DEFAULT NULL"),
                "delivered_at must be nullable: NULL means the command has not been sent yet");
        assertTrue(table.contains("`next_attempt_at` DATETIME(3)     DEFAULT NULL"),
                "next_attempt_at carries the response deadline");
        assertTrue(table.contains("`attempt_count`   INT             NOT NULL DEFAULT 0"),
                "the retry budget is counted per task");
        assertTrue(table.contains("`max_attempts`    INT             NOT NULL DEFAULT 3"),
                "the default budget must match ExecutorUpdateService.MAX_ATTEMPTS");
        assertTrue(table.contains("`request_id`      VARCHAR(64)     NOT NULL"),
                "the client dedupes by request id");
        assertTrue(table.contains("UNIQUE KEY `uk_request` (`request_id`)"),
                "a request id must be globally unique so a result frame finds exactly one task");
        assertTrue(table.contains("KEY `idx_executor` (`tenant_id`, `executor_id`, `is_deleted`)"),
                "findActiveByExecutor is on the hot heartbeat path");
        assertTrue(table.contains("KEY `idx_status_next_attempt` (`status`, `next_attempt_at`)"),
                "the scan filters by status and deadline");
        assertTrue(table.contains("`last_error`      VARCHAR(1024)"),
                "the column must be wider than the service's 1000-character truncation");
    }

    /**
     * The auto-upgrade switch is one global flag that nobody flips at runtime, so it is a deployment
     * setting in application.yml — default on. Neither schema file may reintroduce a table for it: a
     * tenant-keyed settings table cannot even express a global switch, which is why the DB carrier was
     * dropped.
     */
    @Test
    void autoUpdateSwitchIsDeploymentConfigurationNotATable() throws Exception {
        for (Path sql : new Path[]{CANONICAL_SCHEMA, MIGRATION}) {
            assertFalse(Files.readString(sql).contains("platform_runtime_config"),
                    sql + " must not carry a table for a global deployment flag");
        }

        String yml = Files.readString(APPLICATION_YML);
        assertTrue(yml.contains("executor-auto-update-enabled: "
                        + "${AUTOWONDER_RUNTIME_EXECUTOR_AUTO_UPDATE_ENABLED:true}"),
                "the switch must live in application.yml under autowonder.runtime and default to on");
    }

    @Test
    void insertLeavesDeliveryToTheSendPath() throws Exception {
        String insert = statement(Files.readString(TASK_MAPPER), "insert");

        assertTrue(insert.contains("#{requestId}") && insert.contains("#{targetVersion}"),
                "insert must persist the correlation id and the pinned target version");
        assertTrue(insert.contains("#{nextAttemptAt}"), "insert seeds the first deadline");
        assertFalse(insert.contains("delivered_at"),
                "insert must leave delivered_at NULL so the scan knows the command was never sent");
        assertFalse(insert.contains("completed_at"), "a new task is not completed");
    }

    @Test
    void activeTaskLookupIsTenantScopedAndPicksTheNewest() throws Exception {
        String select = statement(Files.readString(TASK_MAPPER), "findActiveByExecutor");

        assertTrue(select.contains("tenant_id = #{tenantId}"), "one tenant must never see another's task");
        assertTrue(select.contains("is_deleted = 0"), "deleted tasks are not in flight");
        assertTrue(select.contains("status IN ('PENDING', 'DRAINING', 'UPDATING')"),
                "the active set must match ExecutorUpdateService.ACTIVE_STATUSES");
        assertTrue(select.contains("ORDER BY id DESC LIMIT 1"),
                "at most one task may be in flight, so the newest wins deterministically");
    }

    @Test
    void requestLookupIgnoresSoftDeletedRows() throws Exception {
        String select = statement(Files.readString(TASK_MAPPER), "findByRequestId");

        assertTrue(select.contains("request_id = #{requestId}"), "the client correlates by request id");
        assertTrue(select.contains("is_deleted = 0"),
                "a deleted task must not be resurrected by a late result frame");
        assertTrue(select.contains("LIMIT 1"), "uk_request already narrows this to one row");
    }

    /**
     * The automatic-upgrade cooldown counts closed AUTO tasks per executor. Every leading predicate is
     * tenant_id + executor_id + is_deleted, which is exactly the {@code idx_executor} prefix, so the
     * feature needs no new index; the two value filters only run over the handful of rows that prefix
     * already leaves.
     */
    @Test
    void theCooldownCountRidesTheExistingExecutorIndex() throws Exception {
        String select = statement(Files.readString(TASK_MAPPER), "countRecentAutoFailures");

        assertTrue(select.contains("SELECT COUNT(1)"), "the caller only needs to know whether any exist");
        assertTrue(select.contains("tenant_id = #{tenantId}") && select.contains("executor_id = #{executorId}"),
                "one tenant's failure must never throttle another tenant's executor");
        assertTrue(select.contains("is_deleted = 0"), "a deleted task is not history worth honouring");
        assertTrue(select.contains("source = 'AUTO'"),
                "a manual or batch upgrade is an operator's decision and stays unthrottled");
        assertTrue(select.contains("status = 'FAILED'"), "only a task that gave up starts a cooldown");
        assertTrue(select.contains("completed_at IS NOT NULL") && select.contains("completed_at &gt;= #{since}"),
                "the window is measured from when the task closed, not from when it was created");

        String table = createTable(Files.readString(CANONICAL_SCHEMA), "executor_update_task");
        assertTrue(table.contains("KEY `idx_executor` (`tenant_id`, `executor_id`, `is_deleted`)"),
                "the columns this count leads with are the ones idx_executor already indexes");
    }

    @Test
    void scanSelectsOnlyLapsedDeadlinesInDeterministicOrder() throws Exception {
        String select = statement(Files.readString(TASK_MAPPER), "listDeliverable");

        assertTrue(select.contains("next_attempt_at IS NULL OR next_attempt_at &lt;= #{now}"),
                "an unstarted task and a lapsed deadline are both due");
        assertTrue(select.contains("ORDER BY COALESCE(next_attempt_at, '1970-01-01') ASC, id ASC"),
                "the oldest waiter goes first, with id as the tie-break");
        assertTrue(select.contains("LIMIT #{limit}"), "the scan is batched");
        assertTrue(select.contains("status IN ('PENDING', 'DRAINING', 'UPDATING')"),
                "terminal tasks are never rescanned");
    }

    @Test
    void failureConsumesAnAttemptAndReopensTheTaskForRetry() throws Exception {
        String update = statement(Files.readString(TASK_MAPPER), "recordFailure");

        assertTrue(update.contains("attempt_count = attempt_count + 1"), "every failure costs one attempt");
        assertTrue(update.contains("delivered_at = NULL"),
                "clearing the send stamp is what makes the scan re-send instead of timing out again");
        assertTrue(update.contains("completed_at = CASE WHEN #{to} = 'FAILED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END"),
                "only giving up closes the task");
        assertTrue(update.contains("status IN ('PENDING', 'DRAINING', 'UPDATING')"),
                "a terminal task must not be reopened by a late scan");
    }

    @Test
    void deliveryAndPostponementOnlyTouchTasksStillInFlight() throws Exception {
        String xml = Files.readString(TASK_MAPPER);

        assertTrue(statement(xml, "markDelivered").contains("SET delivered_at = #{deliveredAt}, "
                        + "next_attempt_at = #{nextAttemptAt}"),
                "delivery stamps both the send time and the new response deadline");
        assertTrue(statement(xml, "postpone").contains("SET next_attempt_at = #{nextAttemptAt}"),
                "postponing must not consume the retry budget");
        for (String id : new String[]{"markDelivered", "postpone"}) {
            assertTrue(statement(xml, id).contains("status IN ('PENDING', 'DRAINING', 'UPDATING')"),
                    id + " must not resurrect a finished task");
        }
        assertTrue(statement(xml, "updateStatus").contains("AND status IN"),
                "updateStatus is guarded by the caller-supplied source states");
    }

    @Test
    void panelQueryNeverRendersAnEmptyInClause() throws Exception {
        String select = statement(Files.readString(TASK_MAPPER), "listLatestByExecutors");

        assertTrue(select.contains("SELECT MAX(id) AS id"), "one row per executor, the newest task");
        assertTrue(select.contains("<otherwise>(NULL)</otherwise>"),
                "an empty id list must degrade to IN (NULL), not the syntax error IN ()");
        assertTrue(select.contains("GROUP BY executor_id"), "the list page needs one entry per executor");
    }

    /**
     * The heartbeat path ships the reported version to presence, so the executor mapper carries no
     * write statement for it at all. The scan that feeds automatic upgrades stays a narrow, stable
     * read over identity columns: the version it needs is fetched per executor from Redis.
     */
    @Test
    void theExecutorMapperNeverWritesAVersionAndTheScanStaysCheap() throws Exception {
        String xml = Files.readString(EXECUTOR_MAPPER);

        assertFalse(xml.contains("last_reported_version"),
                "the version lives in Redis presence; the mapper must not mirror it into a column");

        String scan = statement(xml, "listForVersionScan");
        assertTrue(scan.contains("is_deleted = 0"), "a deleted executor is never upgraded");
        assertTrue(scan.contains("ORDER BY id ASC"), "the scan must be stable across nodes");
        assertFalse(scan.contains("token_ref") || scan.contains("launch_config"),
                "the scan must not drag secrets or wide columns into memory");
    }

    private static String createTable(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "` (");
        assertTrue(start >= 0, "missing CREATE TABLE for " + table);
        int end = sql.indexOf(") ENGINE=InnoDB", start);
        assertTrue(end > start, "unterminated CREATE TABLE for " + table);
        return sql.substring(start, end);
    }

    /** Extracts one whole mapper statement, nested {@code <choose>} blocks included. */
    private static String statement(String xml, String id) {
        int attribute = xml.indexOf("id=\"" + id + "\"");
        assertTrue(attribute >= 0, "mapper must contain the " + id + " statement");
        int open = xml.lastIndexOf('<', attribute);
        String name = xml.substring(open + 1, xml.indexOf(' ', open));
        int close = xml.indexOf("</" + name + ">", attribute);
        assertTrue(close > attribute, "unterminated " + id + " statement");
        return xml.substring(open, close + name.length() + 3);
    }
}
