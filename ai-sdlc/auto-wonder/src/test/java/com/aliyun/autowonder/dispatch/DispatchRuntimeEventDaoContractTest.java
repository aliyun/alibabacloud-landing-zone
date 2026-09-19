package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchRuntimeEventDaoContractTest {

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
    void dispatchAfterSeqCursorRequiresTenantIsolationAndStrictlyGreaterSeq() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByDispatchAfterSeq\"");
        assertTrue(start >= 0, "listByDispatchAfterSeq select must exist");
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, "listByDispatchAfterSeq select must be closed");
        String sql = xml.substring(start, end);
        String normalized = sql.replace("&gt;", ">").replace("&lt;", "<");

        assertTrue(normalized.contains("tenant_id = #{tenantId}"));
        assertTrue(normalized.contains("dispatch_id = #{dispatchId}"));
        assertTrue(normalized.contains("seq IS NULL OR seq > #{afterSeq}"));
        assertFalse(normalized.contains("seq >= #{afterSeq}"),
                "afterSeq cursor must be strictly greater, otherwise reconnect re-delivers the cursor row");
        assertTrue(normalized.contains("ORDER BY COALESCE(seq, id) ASC, id ASC"));
        assertFalse(sql.contains("${"));
    }
}
