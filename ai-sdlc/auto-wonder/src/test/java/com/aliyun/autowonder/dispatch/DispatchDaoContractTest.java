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

    @Test
    void mapsAndWritesExecutionSourceExplicitly() throws Exception {
        String xml = mapperXml();

        assertTrue(xml.contains("column=\"source_type\" property=\"sourceType\""));
        assertTrue(mapperStatement(xml, "insert", "insert").contains("source_type"));
        assertTrue(mapperStatement(xml, "insert", "insert").contains("#{sourceType}"));
    }

    @Test
    void workitemQueriesArePermanentlyFencedToWorkitemSource() throws Exception {
        String xml = mapperXml();

        for (String id : java.util.List.of("findMaxAttempt", "listByWorkitem",
                "listLatestByWorkitemIds", "listByWorkitemIds",
                "listLatestByWorkitemAndAgent", "listSucceededByWorkitem")) {
            assertTrue(mapperStatement(xml, id, "select").contains("source_type = 'WORKITEM'"),
                    id + " must not read scheduled-run dispatches with the same numeric id");
        }
    }

    @Test
    void batchWorkitemQueriesFenceOuterAndDerivedRowsToExplicitTenant() throws Exception {
        String xml = mapperXml();

        String latest = mapperStatement(xml, "listLatestByWorkitemIds", "select");
        assertTrue(occurrences(latest, "tenant_id = #{tenantId}") >= 2,
                "latest query must tenant-fence both outer and derived selects");
        String all = mapperStatement(xml, "listByWorkitemIds", "select");
        assertTrue(all.contains("tenant_id = #{tenantId}"));
    }

    @Test
    void genericSubjectQueriesIncludeTenantSourceAndNumericId() throws Exception {
        String xml = mapperXml();

        for (String id : java.util.List.of("findMaxAttemptBySource", "listBySource",
                "listLatestBySourceAndAgent", "listSucceededBySource")) {
            String sql = mapperStatement(xml, id, "select");
            assertTrue(sql.contains("tenant_id = #{tenantId}"), id);
            assertTrue(sql.contains("source_type = #{sourceType}"), id);
            assertTrue(sql.contains("workitem_id = #{sourceId}"), id);
        }
    }

    @Test
    void globalCapacityAndPendingScansStaySourceAgnostic() throws Exception {
        String xml = mapperXml();

        assertFalse(mapperStatement(xml, "countActiveByExecutor", "select").contains("source_type"));
        assertFalse(mapperStatement(xml, "listOldestPendingByAgent", "select").contains("source_type"));
        assertFalse(mapperStatement(xml, "listStuck", "select").contains("source_type"));
    }

    @Test
    void legacyTenantListWorkitemFilterCannotMatchScheduledRunId() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("id=\"tenantFilter\"");
        int end = xml.indexOf("</sql>", start);
        String filter = xml.substring(start, end);

        assertTrue(filter.contains("workitemId != null"));
        assertTrue(filter.contains("source_type = 'WORKITEM'"));
    }

    private String mapperXml() throws Exception {
        return new String(getClass().getResourceAsStream("/mapping/DispatchDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static String mapperStatement(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "missing mapper statement " + id);
        int end = xml.indexOf("</update>", start);
        assertTrue(end >= 0, "missing closing update for " + id);
        return xml.substring(start, end);
    }

    private static String mapperStatement(String xml, String id, String element) {
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "missing mapper statement " + id);
        int end = xml.indexOf("</" + element + ">", start);
        assertTrue(end >= 0, "missing closing " + element + " for " + id);
        return xml.substring(start, end);
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(fragment, at)) >= 0; at += fragment.length()) {
            count++;
        }
        return count;
    }
}
