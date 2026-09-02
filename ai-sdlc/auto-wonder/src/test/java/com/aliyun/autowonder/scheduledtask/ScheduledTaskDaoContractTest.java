package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskDaoContractTest {

    private static final List<String> TASK_COLUMNS = List.of(
            "id", "workspace_id", "name", "instruction_md", "squad_id", "initial_agent_id",
            "schedule_type", "run_at", "cron_expression", "timezone", "session_mode",
            "overlap_policy", "misfire_policy", "start_deadline_seconds",
            "affinity_timeout_seconds", "status", "next_fire_at", "last_fire_at",
            "gmt_create", "gmt_modified", "creator_id", "modifier_id", "is_deleted", "version");

    private static final List<String> RUN_COLUMNS = List.of(
            "id", "workspace_id", "scheduled_task_id", "trigger_key", "trigger_type",
            "scheduled_at", "started_at", "finished_at", "status", "skip_reason", "squad_id",
            "initial_agent_id", "current_agent_id", "sdlc_id", "current_step_id", "session_mode",
            "resume_from_run_id", "degraded_resume", "degraded_reason", "execution_snapshot_json",
            "result_summary", "error", "owner_id", "gmt_create", "gmt_modified", "creator_id",
            "modifier_id", "version");

    @Test
    void runDaoExposesInsertAndCasMethods() throws Exception {
        assertNotNull(ScheduledTaskRunDao.class.getMethod("insert", ScheduledTaskRunDO.class));
        assertNotNull(ScheduledTaskRunDao.class.getMethod(
                "updateStatus", Long.class, Long.class, String.class, String.class,
                Integer.class, Long.class));
        assertNotNull(ScheduledTaskRunDao.class.getMethod(
                "initializeExecution", Long.class, Long.class, String.class, Long.class,
                Long.class, Long.class, Integer.class, Long.class));
        assertNotNull(ScheduledTaskRunDao.class.getMethod(
                "updateTerminalResult", Long.class, Long.class, String.class, String.class,
                String.class, String.class, Integer.class, Long.class));
        assertNotNull(ScheduledTaskRunDao.class.getMethod(
                "markDegraded", Long.class, Long.class, String.class, Long.class,
                Integer.class, Long.class));
        assertNotNull(ScheduledTaskDao.class.getMethod(
                "claimNextFire", Long.class, Long.class, Integer.class,
                java.util.Date.class, java.util.Date.class, java.util.Date.class,
                String.class, Long.class));
        assertNotNull(ScheduledTaskDao.class.getMethod(
                "findByIdForUpdate", Long.class, Long.class));
    }

    @Test
    void taskMapperMapsEveryColumnAndDefinesWorkspaceSafeCrud() throws Exception {
        String xml = mapper("ScheduledTaskDao.xml");
        for (String column : TASK_COLUMNS) {
            assertTrue(xml.contains("column=\"" + column + "\""), "missing task mapping: " + column);
        }

        String insert = statement(xml, "insert");
        assertTrue(insert.contains("INSERT INTO scheduled_task"));
        assertFalse(insert.contains("#{isDeleted}"));
        assertFalse(insert.contains("#{version}"));
        assertWorkspaceAndLive(statement(xml, "findById"));
        String findByIdForUpdate = statement(xml, "findByIdForUpdate");
        assertWorkspaceAndLive(findByIdForUpdate);
        assertTrue(findByIdForUpdate.contains("FOR UPDATE"));
        String list = statement(xml, "listByWorkspace");
        assertWorkspaceAndLive(list);
        assertTrue(list.contains("ORDER BY id DESC"));
        assertTrue(list.contains("LIMIT #{limit} OFFSET #{offset}"));
        assertWorkspaceAndLive(statement(xml, "update"));
        assertTrue(statement(xml, "update").contains("version = #{version}"));
        assertWorkspaceAndLive(statement(xml, "updateStatus"));
    }

    @Test
    void dueScanAndClaimMatchTheDueIndexAndUseOptimisticCas() throws Exception {
        String xml = mapper("ScheduledTaskDao.xml");
        String due = statement(xml, "findDue");
        assertTrue(due.contains("status = 'ACTIVE'"));
        assertTrue(due.contains("is_deleted = 0"));
        assertTrue(due.contains("next_fire_at &lt;= #{now}"));
        assertTrue(due.contains("ORDER BY next_fire_at ASC, id ASC"));
        assertTrue(due.contains("LIMIT #{limit}"));
        assertFalse(due.contains("workspace_id"), "due scan is intentionally global and index-first");

        String claim = statement(xml, "claimNextFire");
        assertTrue(claim.contains("id = #{id}"));
        assertTrue(claim.contains("workspace_id = #{workspaceId}"));
        assertTrue(claim.contains("status = 'ACTIVE'"));
        assertTrue(claim.contains("version = #{expectedVersion}"));
        assertTrue(claim.contains("next_fire_at = #{expectedNextFireAt}"));
        assertTrue(claim.contains("next_fire_at IS NULL"));
        assertTrue(claim.contains("is_deleted = 0"));
        assertTrue(claim.contains("version = version + 1"));
        assertTrue(claim.contains("#{status} IN ('ACTIVE', 'EXHAUSTED')"));

        String updateStatus = statement(xml, "updateStatus");
        assertTrue(updateStatus.contains(
                "#{targetStatus} IN ('ACTIVE', 'PAUSED', 'EXHAUSTED', 'ARCHIVED')"));
    }

    @Test
    void runMapperMapsEveryColumnAndLetsDuplicateKeysSurface() throws Exception {
        String xml = mapper("ScheduledTaskRunDao.xml");
        for (String column : RUN_COLUMNS) {
            assertTrue(xml.contains("column=\"" + column + "\""), "missing run mapping: " + column);
        }
        String insert = statement(xml, "insert");
        assertTrue(insert.contains("INSERT INTO scheduled_task_run"));
        assertFalse(insert.contains("IGNORE"));
        assertFalse(insert.contains("ON DUPLICATE"));
        assertFalse(insert.contains("#{degradedResume}"));
        assertFalse(insert.contains("#{version}"));
        assertWorkspace(statement(xml, "findByTriggerKey"));
        assertWorkspace(statement(xml, "findById"));
        String list = statement(xml, "listByTask");
        assertWorkspace(list);
        assertTrue(list.contains("scheduled_task_id = #{scheduledTaskId}"));
        assertTrue(list.contains("ORDER BY id DESC"));
        assertTrue(list.contains("LIMIT #{limit} OFFSET #{offset}"));
        assertFalse(xml.contains("is_deleted"), "scheduled_task_run has no soft-delete column");
    }

    @Test
    void runQueueRecoveryAndStateChangesAreBoundedAndCasGuarded() throws Exception {
        String xml = mapper("ScheduledTaskRunDao.xml");
        String active = statement(xml, "findActiveByTask");
        assertWorkspace(active);
        assertTrue(active.contains("status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELED', 'SKIPPED')"));

        String queued = statement(xml, "findNextQueued");
        assertWorkspace(queued);
        assertTrue(queued.contains("status = 'QUEUED'"));
        assertTrue(queued.contains("ORDER BY scheduled_at ASC, id ASC"));
        assertTrue(queued.contains("LIMIT 1"));

        String stale = statement(xml, "listStaleStarting");
        assertTrue(stale.contains("status = 'STARTING'"));
        assertTrue(stale.contains("gmt_modified &lt; #{before}"));
        assertTrue(stale.contains("ORDER BY gmt_modified ASC, id ASC"));
        assertTrue(stale.contains("LIMIT #{limit}"));

        String update = statement(xml, "updateStatus");
        assertRunMutationCas(update);
        assertTrue(update.contains("status = #{expectedStatus}"));
        assertTrue(update.contains("#{targetStatus} IN ('QUEUED', 'STARTING', 'WAITING_EXECUTOR', 'RUNNING', 'WAITING_HUMAN', 'PAUSED')"));
        assertFalse(update.contains(
                "#{targetStatus} IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELED', 'SKIPPED')"));

        String assignment = statement(xml, "updateCurrentAssignment");
        assertRunMutationCas(assignment);
        assertTrue(assignment.contains("current_agent_id = #{currentAgentId}"));
        assertTrue(assignment.contains("current_step_id = #{currentStepId}"));
        assertTrue(assignment.contains("sdlc_id = #{sdlcId}"));

        String initialize = statement(xml, "initializeExecution");
        assertRunMutationCas(initialize);
        assertTrue(initialize.contains("sdlc_id = #{sdlcId}"));
        assertTrue(initialize.contains("current_agent_id = #{currentAgentId}"));
        assertTrue(initialize.contains("current_step_id = #{currentStepId}"));
        assertTrue(initialize.contains("status = 'STARTING'"));
        assertTrue(initialize.contains("started_at = COALESCE(started_at, NOW(3))"));
        assertTrue(initialize.contains("status = #{expectedStatus}"));
        assertTrue(initialize.contains("#{expectedStatus} IN ('QUEUED', 'STARTING')"));

        String result = statement(xml, "updateTerminalResult");
        assertRunMutationCas(result);
        assertTrue(result.contains("status = #{targetStatus}"));
        assertTrue(result.contains("result_summary = #{resultSummary}"));
        assertTrue(result.contains("error = #{error}"));
        assertTrue(result.contains("finished_at = COALESCE(finished_at, NOW(3))"));
        assertTrue(result.contains("status = #{expectedStatus}"));
        assertTrue(result.contains("#{targetStatus} IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELED', 'SKIPPED')"));

        String degraded = statement(xml, "markDegraded");
        assertRunMutationCas(degraded);
        assertTrue(degraded.contains("degraded_resume = 1"));
        assertTrue(degraded.contains("degraded_reason = #{degradedReason}"));
        assertTrue(degraded.contains("resume_from_run_id = #{resumeFromRunId}"));
    }

    private static void assertWorkspaceAndLive(String sql) {
        assertWorkspace(sql);
        assertTrue(sql.contains("is_deleted = 0"));
    }

    private static void assertWorkspace(String sql) {
        assertTrue(sql.contains("workspace_id = #{workspaceId}"));
    }

    private static void assertRunMutationCas(String sql) {
        assertWorkspace(sql);
        assertTrue(sql.contains("version = #{expectedVersion}"));
        assertTrue(sql.contains("version = version + 1"));
        assertTrue(sql.contains(
                "status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELED', 'SKIPPED')"));
    }

    private String mapper(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/mapping/" + name)) {
            assertNotNull(input, "missing mapper resource " + name);
            Configuration configuration = new Configuration();
            new XMLMapperBuilder(input, configuration, "mapping/" + name,
                    configuration.getSqlFragments()).parse();
        }
        return new String(getClass().getResourceAsStream("/mapping/" + name).readAllBytes(),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private static String statement(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "missing mapper statement " + id);
        int end = xml.indexOf('>', start);
        String tag = xml.substring(xml.lastIndexOf('<', start), end + 1);
        String closing = tag.startsWith("<select") ? "</select>"
                : tag.startsWith("<insert") ? "</insert>" : "</update>";
        int close = xml.indexOf(closing, end);
        assertTrue(close >= 0, "missing closing tag for " + id);
        return xml.substring(start, close);
    }
}
