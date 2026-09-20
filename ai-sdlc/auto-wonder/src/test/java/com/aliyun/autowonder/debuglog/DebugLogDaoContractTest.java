package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.tenant.TenantTables;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugLogDaoContractTest {

    @Test
    void mapperRegistersEveryStatementAndParses() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId("autowonder-source-aware");
        try (InputStream input = getClass().getResourceAsStream("/mapping/DebugLogDao.xml")) {
            assertNotNull(input, "missing mapper DebugLogDao.xml");
            new XMLMapperBuilder(input, configuration, "mapping/DebugLogDao.xml",
                    configuration.getSqlFragments()).parse();
        }
        for (String id : List.of("insert", "findByDispatchId", "updateOnIssue", "updateOnResult",
                "listForQuery", "listPendingOlderThan", "markReconciled")) {
            assertTrue(configuration.hasStatement(
                    "com.aliyun.autowonder.debuglog.DebugLogDao." + id), "missing statement " + id);
        }
    }

    @Test
    void statementsCarryTenantGuardsAndPendingFences() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/DebugLogDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        String listForQuery = statement(xml, "listForQuery");
        assertTrue(listForQuery.contains("tenant_id = #{tenantId}"));
        assertTrue(listForQuery.contains("source_type = #{sourceType}"));
        assertTrue(listForQuery.contains("source_id = #{sourceId}"));
        assertTrue(listForQuery.contains("ORDER BY run_no ASC, id ASC"));

        String pending = statement(xml, "listPendingOlderThan");
        assertTrue(pending.contains("status = 'PENDING'"));
        assertTrue(pending.contains("gmt_modified &lt; FROM_UNIXTIME(#{beforeEpochMillis} / 1000)"));

        String issue = statement(xml, "updateOnIssue");
        assertTrue(issue.contains("status = 'PENDING'"));
        assertTrue(issue.contains("status &lt;&gt; 'UPLOADED'"),
                "re-issue must never resurrect an UPLOADED row");

        String result = statement(xml, "updateOnResult");
        assertTrue(result.contains("WHERE id = #{id} AND status &lt;&gt; 'UPLOADED'"),
                "result writeback must never regress an UPLOADED row (S9 monotonic guard;"
                        + " FAILED→UPLOADED 补传仍允许)");
        assertTrue(result.contains("error_message = #{errorMessage}"),
                "error_message stays unconditionally overwritten (UPLOADED 行可携带收尾注记)");
        assertFalse(result.contains("<if test=\"errorMessage"),
                "error_message must not gain an <if> guard");

        String reconciled = statement(xml, "markReconciled");
        assertTrue(reconciled.contains("WHERE id = #{id} AND status = 'PENDING'"));
    }

    @Test
    void debugLogJoinsTenantWhitelistForDefenseInDepth() {
        assertTrue(TenantTables.TABLES.contains("debug_log"),
                "debug_log must be tenant-rewritten for user-context reads");
    }

    private static String statement(String xml, String id) {
        Matcher matcher = Pattern.compile(
                "(?s)<(select|insert|update)[^>]*id=\"" + id + "\".*?</\\1>").matcher(xml);
        assertTrue(matcher.find(), "missing statement " + id);
        return matcher.group();
    }
}
