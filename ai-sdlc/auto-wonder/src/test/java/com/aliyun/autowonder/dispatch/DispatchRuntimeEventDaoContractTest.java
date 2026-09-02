package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
}
