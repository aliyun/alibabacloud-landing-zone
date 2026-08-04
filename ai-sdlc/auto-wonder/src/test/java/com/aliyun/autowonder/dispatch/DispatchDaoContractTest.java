package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchDaoContractTest {

    @Test
    void mapperDefinesCapacityQueueAndLeaseOperations() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/DispatchDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("id=\"countActiveByExecutor\""));
        assertTrue(xml.contains("id=\"listOldestPendingByAgent\""));
        assertTrue(xml.contains("id=\"returnDispatchedToPending\""));
        assertTrue(xml.contains("id=\"returnPackagingToPending\""));
        assertTrue(xml.contains("id=\"touchOwnedActive\""));
        String touchOwnedActive = mapperStatement(xml, "touchOwnedActive");
        assertTrue(touchOwnedActive.contains("'PACKAGING'"));
        assertTrue(touchOwnedActive.contains("'DISPATCHED'"));
        assertTrue(touchOwnedActive.contains("'ACKED'"));
        assertTrue(touchOwnedActive.contains("'RUNNING'"));
        assertFalse(touchOwnedActive.contains("'PAUSING'"));
        assertTrue(xml.contains("ORDER BY gmt_create ASC, id ASC"));
        assertTrue(xml.contains("executor_id = NULL"));
        assertTrue(xml.contains("package_oss_ref = NULL"));
        assertTrue(xml.contains("id=\"listLatestByWorkitemAndAgent\""));
        assertTrue(xml.contains("ORDER BY id DESC"));
        assertFalse(xml.contains("findLatestByWorkitemAndAgentBefore"));
    }

    private static String mapperStatement(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "missing mapper statement " + id);
        int end = xml.indexOf("</update>", start);
        assertTrue(end >= 0, "missing closing update for " + id);
        return xml.substring(start, end);
    }
}
