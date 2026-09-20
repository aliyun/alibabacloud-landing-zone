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

    @Test
    void insertCarriesKindColumn() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String body = statementBody(xml, "insert").replaceAll("\\s+", " ").trim();
            assertTrue(body.contains("kind"), "insert must carry the kind column");
            assertTrue(body.contains("#{kind}"), "insert must bind #{kind}");
        }
    }

    @Test
    void listQueryFiltersByKindWhenProvided() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String query = selectBody(xml, "list").replaceAll("\\s+", " ").trim();
            assertTrue(query.contains("<if test=\"kind != null\">AND kind = #{kind}</if>"),
                    "list must support an optional kind filter");
        }
    }

    @Test
    void findPlatformAgentIsTenantScopedAndExcludesDeleted() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String query = selectBody(xml, "findPlatformAgent").replaceAll("\\s+", " ").trim();
            assertTrue(query.contains("tenant_id = #{tenantId}"),
                    "platform agent lookup must be tenant scoped");
            assertTrue(query.contains("kind = 'PLATFORM'"),
                    "platform agent lookup must match the PLATFORM kind");
            assertTrue(query.contains("is_deleted = 0"),
                    "platform agent lookup must exclude soft deleted rows");
        }
    }

    @Test
    void avatarUpdateStaysTenantScopedAndOptimisticallyLocked() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String update = updateBody(xml, "updateAvatarUrl").replaceAll("\\s+", " ").trim();

            assertTrue(update.contains("avatar_url = #{avatarUrl}"),
                    "updateAvatarUrl must persist the new avatar");
            assertTrue(update.contains("tenant_id = #{tenantId}"),
                    "updateAvatarUrl must stay tenant-scoped");
            assertTrue(update.contains("version = #{version}"),
                    "updateAvatarUrl must keep the optimistic-lock predicate");
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

    private String statementBody(String xml, String id) {
        String startTag = "<insert id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertTrue(start >= 0, "Missing insert " + id);
        int bodyStart = xml.indexOf('>', start) + 1;
        int bodyEnd = xml.indexOf("</insert>", bodyStart);
        assertTrue(bodyStart > 0 && bodyEnd > bodyStart, "Malformed insert " + id);
        return xml.substring(bodyStart, bodyEnd);
    }

    private String updateBody(String xml, String id) {
        String startTag = "<update id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertTrue(start >= 0, "Missing update " + id);
        int bodyStart = xml.indexOf('>', start) + 1;
        int bodyEnd = xml.indexOf("</update>", bodyStart);
        assertTrue(bodyStart > 0 && bodyEnd > bodyStart, "Malformed update " + id);
        return xml.substring(bodyStart, bodyEnd);
    }
}
