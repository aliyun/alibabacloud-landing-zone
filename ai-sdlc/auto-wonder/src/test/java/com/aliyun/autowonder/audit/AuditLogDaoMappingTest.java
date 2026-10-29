package com.aliyun.autowonder.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogDaoMappingTest {

    private static final String SHARED_FRAGMENT =
            "com.aliyun.autowonder.audit.AuditLogDao.riskLevelCase";

    @Test
    void riskLevelFragmentUsesExactActionMatch() throws IOException {
        String fragment = sectionOf(auditXml(), "id=\"riskLevelCase\"", "</sql>");

        assertTrue(fragment.contains("WHEN al.action IN ('DELETE', 'FORCE_PUBLISH', 'ROLE_CHANGE') THEN 'high'"));
        assertTrue(fragment.contains("WHEN al.action IN ('UPDATE', 'REJECT', 'RETRY') THEN 'medium'"));
        assertTrue(fragment.contains("ELSE 'low'"));
        assertFalse(fragment.contains("LIKE"));
        assertFalse(fragment.contains("UPDATE_WORKITEM_STATUS"));
    }

    @Test
    void insightsAuditQueriesReuseTheSharedFragmentWithoutACopy() throws IOException {
        String xml = insightsXml();

        for (String statement : new String[]{"id=\"listAuditItems\"", "id=\"countAuditItems\""}) {
            String query = sectionOf(xml, statement, "</select>");
            assertTrue(query.contains(SHARED_FRAGMENT), statement);
            assertFalse(query.contains("THEN 'high'"), statement + " must not duplicate the risk CASE");
        }
    }

    @Test
    void auditSearchDerivesRiskLevelAndFallsBackToHumanForMissingActorType() throws IOException {
        String xml = auditXml();
        String search = sectionOf(xml, "id=\"search\"", "</select>");
        String condition = sectionOf(xml, "id=\"searchCondition\"", "</sql>");

        assertTrue(search.contains("AS risk_level"));
        assertTrue(search.contains("<include refid=\"riskLevelCase\"/>"), "search must reuse the risk fragment");
        assertTrue(condition.contains("<include refid=\"riskLevelCase\"/>"), "searchCondition must filter by the risk fragment");
        assertTrue(condition.contains("COALESCE(JSON_UNQUOTE(JSON_EXTRACT"));
        assertTrue(condition.contains("'HUMAN'"));
        assertTrue(condition.contains("riskLevel"));
        assertFalse(condition.contains("LIKE '"));
    }

    private static String auditXml() throws IOException {
        return Files.readString(Path.of("src/main/resources/mapping/AuditLogDao.xml"));
    }

    private static String insightsXml() throws IOException {
        return Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
    }

    private static String sectionOf(String xml, String marker, String terminator) {
        int start = xml.indexOf(marker);
        assertTrue(start >= 0, marker);
        int end = xml.indexOf(terminator, start);
        return xml.substring(start, end < 0 ? xml.length() : end);
    }
}
