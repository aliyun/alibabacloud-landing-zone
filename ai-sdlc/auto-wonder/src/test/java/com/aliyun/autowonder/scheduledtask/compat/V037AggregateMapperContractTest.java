package com.aliyun.autowonder.scheduledtask.compat;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V037AggregateMapperContractTest {

    private static final String LEGACY = "autowonder-legacy";
    private static final String SOURCE_AWARE = "autowonder-source-aware";

    @Test
    void dashboardWorkitemAggregatesUseSchemaSpecificDispatchFences() throws Exception {
        Configuration legacy = mybatis("DashboardDao.xml", LEGACY);
        Configuration sourceAware = mybatis("DashboardDao.xml", SOURCE_AWARE);
        String namespace = "com.aliyun.autowonder.dashboard.DashboardDao.";

        // Rendering a KPI statement also verifies the database-specific fence
        // included by endToEndSuccessfulWorkitems inside its derived table.
        assertWorkitemSql(legacy, sourceAware, namespace + "countTodayCompletedTasks");
        for (String id : List.of("squadInProgressWorkitems", "listRunningFeed", "listRecentFeed",
                "listRunningWorkitems", "listAgentRunning")) {
            assertWorkitemSql(legacy, sourceAware, namespace + id);
        }
    }

    @Test
    void dashboardGlobalDispatchAggregatesRemainSourceNeutral() throws Exception {
        Configuration legacy = mybatis("DashboardDao.xml", LEGACY);
        Configuration sourceAware = mybatis("DashboardDao.xml", SOURCE_AWARE);
        String namespace = "com.aliyun.autowonder.dashboard.DashboardDao.";

        for (String id : List.of("countRunningDispatches", "countQueuedDispatches",
                "countTodaySucceeded", "countTodayFailedOrTimeout", "countTodayRetries",
                "avgTodaySuccessDurationMinutes", "countActiveSquads", "squadLineAggregates",
                "onlineWorkstations")) {
            assertSourceNeutral(sql(legacy, namespace + id), id);
            assertSourceNeutral(sql(sourceAware, namespace + id), id);
        }
    }

    @Test
    void runtimeEventWorkitemLookupUsesSchemaSpecificDispatchFence() throws Exception {
        Configuration legacy = mybatis("DispatchRuntimeEventDao.xml", LEGACY);
        Configuration sourceAware = mybatis("DispatchRuntimeEventDao.xml", SOURCE_AWARE);
        String namespace = "com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.";

        String legacyWorkitem = sql(legacy, namespace + "listByWorkitem");
        String sourceWorkitem = sql(sourceAware, namespace + "listByWorkitem");
        for (String query : List.of(legacyWorkitem, sourceWorkitem)) {
            assertTrue(query.contains("EXISTS"), query);
            assertTrue(query.contains("d.id = e.dispatch_id"), query);
            assertTrue(query.contains("d.tenant_id = ?"), query);
            assertTrue(query.contains("d.workitem_id = ?"), query);
            assertTrue(query.contains("d.is_deleted = 0"), query);
        }
        assertSourceNeutral(legacyWorkitem, "legacy listByWorkitem");
        assertEveryDispatchReferenceIsWorkitemFenced(sourceWorkitem);

        for (String id : List.of("listByDispatch", "findLatestByDispatchAndType")) {
            assertSourceNeutral(sql(legacy, namespace + id), id);
            assertSourceNeutral(sql(sourceAware, namespace + id), id);
        }
    }

    private void assertWorkitemSql(Configuration legacy, Configuration sourceAware, String id) {
        String legacySql = sql(legacy, id);
        String sourceSql = sql(sourceAware, id);
        assertSourceNeutral(legacySql, id + " legacy");
        assertEveryDispatchReferenceIsWorkitemFenced(sourceSql);
    }

    private static void assertEveryDispatchReferenceIsWorkitemFenced(String sql) {
        long dispatchReferences = java.util.regex.Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+dispatch\\s+d\\b")
                .matcher(sql).results().count();
        long workitemFences = java.util.regex.Pattern.compile("(?i)\\bd\\.source_type\\s*=\\s*'WORKITEM'")
                .matcher(sql).results().count();
        assertTrue(dispatchReferences > 0, sql);
        assertTrue(workitemFences == dispatchReferences,
                "Every Dispatch alias in a Workitem query must be fenced: " + sql);
    }

    private static void assertSourceNeutral(String sql, String label) {
        assertFalse(sql.contains("source_type"), label + ": " + sql);
    }

    private Configuration mybatis(String resource, String databaseId) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        try (InputStream input = getClass().getResourceAsStream("/mapping/" + resource)) {
            assertNotNull(input, "missing mapper " + resource);
            new XMLMapperBuilder(input, configuration, "mapping/" + resource,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String id) {
        MappedStatement statement = configuration.getMappedStatement(id);
        return statement.getBoundSql(arguments()).getSql().replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> arguments() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("workspaceId", 1L);
        arguments.put("workitemId", 2L);
        arguments.put("dispatchId", 3L);
        arguments.put("agentId", 4L);
        arguments.put("eventType", "PROGRESS");
        arguments.put("limit", 10);
        return arguments;
    }
}
