package com.aliyun.autowonder.dashboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardDaoMappingTest {

    @Test
    void completedTaskKpiQueriesUseEndToEndSuccessfulWorkitems() throws IOException {
        String xml = readMapping();
        assertUsesEndToEndSuccessDefinition(sqlBody(xml, "endToEndSuccessfulWorkitems"));
        assertUsesSuccessfulWorkitemScope(selectBody(xml, "countTodayCompletedTasks"));
        assertUsesSuccessfulWorkitemScope(selectBody(xml, "countWeekCompletedTasks"));
        assertUsesSuccessfulWorkitemScope(selectBody(xml, "avgTodayCompletedTaskDurationMinutes"));
    }

    @Test
    void kpiDetailListQueriesReuseCountDefinitions() throws IOException {
        String xml = readMapping();

        String todayList = selectBody(xml, "listTodayCompletedWorkitems");
        String normalizedToday = todayList.replaceAll("\\s+", " ").trim();
        assertTrue(normalizedToday.contains("<include refid=\"endToEndSuccessfulWorkitems\"/>"));
        assertTrue(normalizedToday.contains("successful_workitem.success_at >= CURDATE()"));
        assertTrue(normalizedToday.contains("workitemId"));
        assertTrue(normalizedToday.contains("title"));

        String weekList = selectBody(xml, "listWeekCompletedWorkitems");
        String normalizedWeek = weekList.replaceAll("\\s+", " ").trim();
        assertTrue(normalizedWeek.contains("<include refid=\"endToEndSuccessfulWorkitems\"/>"));
        assertTrue(normalizedWeek.contains("DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)"));
        assertTrue(normalizedWeek.contains("workitemId"));

        String runningList = selectBody(xml, "listRunningWorkitems");
        String normalizedRunning = runningList.replaceAll("\\s+", " ").trim();
        assertTrue(normalizedRunning.contains("d.status = 'RUNNING'"));
        assertTrue(normalizedRunning.contains("d.workitem_id IS NOT NULL"));
        assertTrue(normalizedRunning.contains("COUNT(*) AS runningDispatchCount"));
        assertTrue(normalizedRunning.contains("GROUP BY d.workitem_id"));
        assertTrue(normalizedRunning.contains("workitemId"));
        assertTrue(normalizedRunning.contains("runningMinutes"));
        assertFalse(normalizedRunning.contains("dispatchId"), "detail list is workitem-level");
        assertFalse(normalizedRunning.contains("LIMIT"));
    }

    @Test
    void overviewWorkitemKpisUseWorkitemScopeQueries() throws IOException {
        String xml = readMapping();

        String todayCreated = selectBody(xml, "countTodayCreatedWorkitems").replaceAll("\\s+", " ").trim();
        assertTrue(todayCreated.contains("FROM workitem"));
        assertTrue(todayCreated.contains("is_deleted = 0"));
        assertTrue(todayCreated.contains("gmt_create >= CURDATE()"));
        assertFalse(todayCreated.contains("dispatch"), "today-created must stay workitem-level");

        String running = selectBody(xml, "countRunningWorkitems").replaceAll("\\s+", " ").trim();
        assertTrue(running.contains("COUNT(DISTINCT d.workitem_id)"));
        assertTrue(running.contains("d.status = 'RUNNING'"));
        assertTrue(running.contains("d.workitem_id IS NOT NULL"));
        assertTrue(running.contains("<include refid=\"workitemDispatchFence\"/>"));
    }

    @Test
    void workitemSemanticsAreFencedWhileGlobalDispatchMetricsRemainSourceAgnostic() throws IOException {
        String xml = readMapping();

        String legacyFence = sqlBody(xml, "workitemDispatchFence", "autowonder-legacy");
        String sourceAwareFence = sqlBody(xml, "workitemDispatchFence", "autowonder-source-aware");
        assertFalse(legacyFence.contains("source_type"));
        assertTrue(sourceAwareFence.contains("d.source_type = 'WORKITEM'"));

        assertUsesWorkitemDispatchFence(sqlBody(xml, "endToEndSuccessfulWorkitems"),
                "endToEndSuccessfulWorkitems");
        assertUsesWorkitemDispatchFence(selectBody(xml, "squadInProgressWorkitems"),
                "squadInProgressWorkitems");
        for (String id : java.util.List.of("listRunningFeed", "listRecentFeed",
                "listRunningWorkitems", "listAgentRunning", "countRunningWorkitems")) {
            assertUsesWorkitemDispatchFence(selectBody(xml, id), id);
        }
        for (String id : java.util.List.of("countRunningDispatches", "countQueuedDispatches",
                "countTodayCreatedWorkitems", "countTodaySucceeded", "countTodayFailedOrTimeout",
                "countTodayRetries", "avgTodaySuccessDurationMinutes", "countActiveSquads",
                "squadLineAggregates", "onlineWorkstations")) {
            assertFalse(selectBody(xml, id).contains("source_type"), id);
        }
    }

    @Test
    void formalDeliveryFenceExcludesInteractionSessionsAndLegacyFenceKeepsResumeModeGuard() throws IOException {
        String xml = readMapping();

        String sourceAware = sqlBody(xml, "formalDeliveryFence", "autowonder-source-aware");
        assertTrue(sourceAware.contains("d.source_type = 'WORKITEM'"));
        assertTrue(sourceAware.contains("COALESCE(d.resume_mode, '') NOT IN ('COMMENT_INTERACTION', 'SIDE_INTERACTION', 'CANONICAL_INTERACTION')"));

        String legacy = sqlBody(xml, "formalDeliveryFence", "autowonder-legacy");
        assertFalse(legacy.contains("source_type"));
        assertTrue(legacy.contains("COALESCE(d.resume_mode, '') NOT IN ('COMMENT_INTERACTION', 'SIDE_INTERACTION', 'CANONICAL_INTERACTION')"));

        for (String id : java.util.List.of("pipelineExecutionStats", "pipelineReworkStats")) {
            assertUsesFormalDeliveryFence(selectBody(xml, id), id);
        }
    }

    @Test
    void pipelineExecutionStatsAggregateOnlyTerminalFormalDispatchesInTheWindow() throws IOException {
        String normalized = selectBody(readMapping(), "pipelineExecutionStats").replaceAll("\\s+", " ").trim();

        assertTrue(normalized.contains("COALESCE(SUM(CASE WHEN d.status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded"));
        assertTrue(normalized.contains("COALESCE(SUM(CASE WHEN d.status IN ('SUCCEEDED','FAILED','TIMEOUT') THEN 1 ELSE 0 END), 0) AS total"));
        assertTrue(normalized.contains("COALESCE(SUM(CASE WHEN d.status IN ('FAILED','TIMEOUT') THEN 1 ELSE 0 END), 0) AS failedOrTimeout"));
        assertTrue(normalized.contains("d.tenant_id = #{tenantId}"));
        assertTrue(normalized.contains("d.is_deleted = 0"));
        assertTrue(normalized.contains("d.gmt_create &gt;= #{start}"));
        assertTrue(normalized.contains("d.gmt_create &lt; #{end}"));
        assertFalse(normalized.toUpperCase().contains("BETWEEN"));
        assertFalse(normalized.contains("CANCELED"));
    }

    @Test
    void pipelineReworkStatsSeparateFailureRetriesAndCommentReworkFromOtherAttemptBumps() throws IOException {
        String xml = readMapping();
        String stats = selectBody(xml, "pipelineReworkStats").replaceAll("\\s+", " ").trim();
        String condition = sqlBody(xml, "reworkDispatchCondition").replaceAll("\\s+", " ").trim();

        assertTrue(stats.contains("COUNT(DISTINCT d.workitem_id) AS executedWorkitems"));
        assertTrue(stats.contains("AS reworkedWorkitems"));
        assertTrue(stats.contains("AS retryCount"));
        assertTrue(stats.contains("<include refid=\"reworkDispatchCondition\"/>"));
        assertTrue(stats.contains("d.gmt_create &gt;= #{start}"));
        assertTrue(stats.contains("d.gmt_create &lt; #{end}"));

        assertTrue(condition.contains("d.resume_mode = 'COMMENT_REWORK'"));
        assertTrue(condition.contains("d.resume_mode IS NULL"));
        assertTrue(condition.contains("d.attempt &gt;= 2"));
        assertTrue(condition.contains("d.idempotency_key REGEXP '^[0-9]+:[0-9]+:[0-9]+$'"));
        assertTrue(condition.contains("d.idempotency_key REGEXP '^WORKITEM:[0-9]+:[0-9]+:[0-9]+$'"));
        assertFalse(condition.contains("attempt > 0"), "handoff/recovery/restart must not count as rework");
    }

    @Test
    void averageWorkitemDurationReusesEndToEndSuccessDefinitionAndStaysNullForEmptyWindows() throws IOException {
        String normalized = selectBody(readMapping(), "avgPipelineWorkitemDurationMinutes")
                .replaceAll("\\s+", " ").trim();

        assertTrue(normalized.contains("<include refid=\"endToEndSuccessfulWorkitems\"/>"));
        assertTrue(normalized.contains("TIMESTAMPDIFF(MINUTE, successful_workitem.gmt_create, successful_workitem.success_at)"));
        assertTrue(normalized.contains("successful_workitem.success_at &gt;= #{start}"));
        assertTrue(normalized.contains("successful_workitem.success_at &lt; #{end}"));
        assertFalse(normalized.toUpperCase().contains("BETWEEN"));
        assertFalse(normalized.contains("COALESCE"), "empty window must stay null so the UI can render a dash");
    }

    private void assertUsesFormalDeliveryFence(String sql, String id) {
        assertTrue(sql.contains("<include refid=\"formalDeliveryFence\"/>"), id);
    }

    private void assertUsesWorkitemDispatchFence(String sql, String id) {
        assertTrue(sql.contains("<include refid=\"workitemDispatchFence\"/>"), id);
    }

    private void assertUsesEndToEndSuccessDefinition(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        assertTrue(normalized.contains("FROM workitem w"));
        assertTrue(normalized.contains("LEFT JOIN status_node sn ON w.status_node_id = sn.id"));
        assertTrue(normalized.contains("LEFT JOIN dispatch latest_dispatch ON latest_dispatch.id = ( SELECT MAX(d.id)"));
        assertTrue(normalized.contains("sn.category = 'DONE'"));
        assertTrue(normalized.contains("w.assignee_type = 'HUMAN'"));
        assertTrue(normalized.contains("COALESCE(sn.category, '') &lt;&gt; 'DONE'"));
        assertTrue(normalized.contains("latest_dispatch.status = 'SUCCEEDED'"));
        assertTrue(normalized.contains("CASE WHEN sn.category = 'DONE' THEN w.gmt_modified ELSE latest_dispatch.gmt_modified END AS success_at"));
    }

    private void assertUsesSuccessfulWorkitemScope(String select) {
        String normalized = select.replaceAll("\\s+", " ").trim();
        assertTrue(normalized.contains("<include refid=\"endToEndSuccessfulWorkitems\"/>"));
        assertTrue(normalized.contains("successful_workitem.success_at"));
        assertFalse(normalized.contains("SELECT COUNT(*) FROM dispatch"));
    }

    private String sqlBody(String xml, String id) {
        String startTag = "<sql id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertTrue(start >= 0, "Missing sql " + id);
        int bodyStart = xml.indexOf('>', start) + 1;
        int bodyEnd = xml.indexOf("</sql>", bodyStart);
        assertTrue(bodyStart > 0 && bodyEnd > bodyStart, "Malformed sql " + id);
        return xml.substring(bodyStart, bodyEnd);
    }

    private String sqlBody(String xml, String id, String databaseId) {
        return elementBody(xml, "sql", id, databaseId);
    }

    private String selectBody(String xml, String id) {
        String startTag = "<select id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertTrue(start >= 0, "Missing select " + id);
        int bodyStart = xml.indexOf('>', start) + 1;
        int bodyEnd = xml.indexOf("</select>", bodyStart);
        assertTrue(bodyStart > 0 && bodyEnd > bodyStart, "Malformed select " + id);
        return xml.substring(bodyStart, bodyEnd);
    }

    private String elementBody(String xml, String element, String id, String databaseId) {
        String startTag = "<" + element + " id=\"" + id + "\"";
        int searchFrom = 0;
        while (true) {
            int start = xml.indexOf(startTag, searchFrom);
            assertTrue(start >= 0, "Missing " + element + " " + id + " for " + databaseId);
            int tagEnd = xml.indexOf('>', start);
            String tag = xml.substring(start, tagEnd + 1);
            if (tag.contains("databaseId=\"" + databaseId + "\"")) {
                int bodyEnd = xml.indexOf("</" + element + ">", tagEnd + 1);
                assertTrue(bodyEnd > tagEnd, "Malformed " + element + " " + id);
                return xml.substring(tagEnd + 1, bodyEnd);
            }
            searchFrom = tagEnd + 1;
        }
    }

    private String readMapping() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/DashboardDao.xml")) {
            assertTrue(in != null, "DashboardDao.xml resource must exist");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
