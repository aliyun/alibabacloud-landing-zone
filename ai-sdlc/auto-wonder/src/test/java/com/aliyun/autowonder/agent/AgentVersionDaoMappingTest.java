package com.aliyun.autowonder.agent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the agent_version mapper statements whose SET clause decides whether a configuration
 * value survives a lifecycle transition. A regression here silently wipes evolutionMode.
 */
class AgentVersionDaoMappingTest {

    private static final String GUARDED_IDENTITY_JSON =
            "<if test=\"identityJson != null\">identity_json = #{identityJson},</if>";

    @Test
    void updateStatusDoesNotWipeIdentityJsonWhenCallerPassesNull() throws Exception {
        String body = updateBody("updateStatus");

        assertTrue(body.contains(GUARDED_IDENTITY_JSON),
                "submit/reject pass identityJson=null, so identity_json must be conditional or evolutionMode is wiped");
        assertTrue(body.contains("status = #{status}"),
                "the transition itself must still be written");
    }

    @Test
    void updateStatusAlwaysResetsReviewScopedColumns() throws Exception {
        String body = updateBody("updateStatus");

        assertTrue(body.contains("reviewer_id = #{reviewerId}"),
                "reviewer_id must be reset unconditionally so a stale reviewer is never carried over");
        assertTrue(body.contains("review_comment = #{reviewComment}"),
                "a resubmission must clear the previous rejection comment");
    }

    @Test
    void updateStatusKeepsTenantIsolationAndOptimisticLock() throws Exception {
        String body = updateBody("updateStatus");

        assertTrue(body.contains("tenant_id = #{tenantId}"),
                "updateStatus must stay tenant-scoped");
        assertTrue(body.contains("version = #{version}"),
                "updateStatus must keep the optimistic-lock predicate");
    }

    @Test
    void updateConfigGuardsIdentityJsonTheSameWay() throws Exception {
        assertTrue(updateBody("updateConfig").contains(GUARDED_IDENTITY_JSON),
                "updateConfig must not clear identity_json when the caller does not change the evolution mode");
    }

    private String updateBody(String id) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/AgentVersionDao.xml")) {
            assertNotNull(in);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String startTag = "<update id=\"" + id + "\"";
            int start = xml.indexOf(startTag);
            assertTrue(start >= 0, "Missing update " + id);
            int bodyStart = xml.indexOf('>', start) + 1;
            int bodyEnd = xml.indexOf("</update>", bodyStart);
            assertTrue(bodyStart > 0 && bodyEnd > bodyStart, "Malformed update " + id);
            return xml.substring(bodyStart, bodyEnd);
        }
    }
}
