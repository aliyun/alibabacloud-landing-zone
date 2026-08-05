package com.aliyun.autowonder.agent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDaoMappingTest {

    @Test
    void handoffRoleLookupUsesPublishedVersionWhileAgentEditIsPendingReview() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String query = selectBody(xml, "findOnlineByRoleCode").replaceAll("\\s+", " ").trim();

            assertTrue(query.contains("a.online_version_id = av.id"),
                    "handoff must resolve the currently published agent version");
            assertTrue(query.contains("av.status = 'APPROVED'"),
                    "only an approved published version is routable");
            assertFalse(query.contains("a.status = 'ONLINE'"),
                    "a pending edit must not hide the still-published worker from handoff routing");
        }
    }

    @Test
    void listQueryFiltersByTenantId() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String query = selectBody(xml, "list").replaceAll("\\s+", " ").trim();

            assertTrue(query.contains("tenant_id = #{tenantId}"),
                    "list must filter by tenant_id to prevent cross-tenant data leakage");
        }
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
}
