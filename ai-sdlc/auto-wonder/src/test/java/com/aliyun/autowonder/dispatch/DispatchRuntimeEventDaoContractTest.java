package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchRuntimeEventDaoContractTest {

    @Test
    void progressProjectionPreservesPlansHistoryAndIsolationInBothSchemas() throws Exception {
        for (String databaseId : java.util.List.of("autowonder-source-aware", "autowonder-legacy")) {
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
            config.setDatabaseId(databaseId);
            try (var input = getClass().getResourceAsStream("/mapping/DispatchRuntimeEventDao.xml")) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config,
                        "mapping/DispatchRuntimeEventDao.xml", config.getSqlFragments()).parse();
            }
            String sql = config.getMappedStatement(DispatchRuntimeEventDao.class.getName() + ".listForProgress")
                    .getBoundSql(java.util.Map.of("tenantId", 7L, "workitemId", 100L)).getSql();
            assertTrue(sql.contains("CASE WHEN event_type = 'workflow.plan_applied' THEN detail_json ELSE NULL END"));
            assertTrue(sql.contains("e.tenant_id = ? AND e.workitem_id = ?"));
            assertTrue(sql.contains("d.id = e.dispatch_id"));
            assertTrue(sql.contains("d.is_deleted = 0"));
            assertTrue(sql.contains("ORDER BY id ASC"));
            assertFalse(sql.contains("LIMIT"));
            assertTrue(sql.contains("d.source_type = 'WORKITEM'") == databaseId.equals("autowonder-source-aware"));
            String fullSql = config.getMappedStatement(DispatchRuntimeEventDao.class.getName() + ".listByWorkitem")
                    .getBoundSql(java.util.Map.of("tenantId", 7L, "workitemId", 100L)).getSql();
            assertFalse(fullSql.contains("CASE WHEN"));
        }
    }

    @Test
    void workitemTimelineRequiresMatchingWorkitemDispatchSource() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByWorkitem\"");
        int end = xml.indexOf("</select>", start);
        String sql = xml.substring(start, end);

        assertTrue(sql.contains("EXISTS"));
        assertTrue(sql.contains("d.tenant_id = #{tenantId}"));
        assertTrue(sql.contains("d.source_type = 'WORKITEM'"));
        assertTrue(sql.contains("d.workitem_id = #{workitemId}"));
        assertTrue(sql.contains("d.id = e.dispatch_id"));
    }

    @Test
    void activityTimelineUsesPersistentArrivalOrderWithoutSequenceFallback() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByDispatchInArrivalOrder\"");
        int end = xml.indexOf("</select>", start);
        String sql = xml.substring(start, end);

        assertTrue(sql.contains("ORDER BY id ASC"));
        assertFalse(sql.contains("COALESCE(seq, id)"));
    }

    @Test
    void dispatchAfterIdCursorRequiresTenantIsolationAndStrictlyGreaterId() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByDispatchAfterId\"");
        assertTrue(start >= 0, "listByDispatchAfterId select must exist");
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, "listByDispatchAfterId select must be closed");
        String sql = xml.substring(start, end);
        String normalized = sql.replace("&gt;", ">").replace("&lt;", "<");

        assertTrue(normalized.contains("tenant_id = #{tenantId}"));
        assertTrue(normalized.contains("dispatch_id = #{dispatchId}"));
        assertTrue(normalized.contains("id > #{afterId}"));
        assertFalse(normalized.contains("id >= #{afterId}"),
                "afterId cursor must be strictly greater, otherwise reconnect re-delivers the cursor row");
        assertTrue(normalized.contains("ORDER BY id ASC"));
        assertFalse(normalized.contains("COALESCE(seq, id)"),
                "failover re-runs restart the runtime-numbered seq, so the cursor must follow arrival id");
        assertFalse(sql.contains("${"));
    }
}
