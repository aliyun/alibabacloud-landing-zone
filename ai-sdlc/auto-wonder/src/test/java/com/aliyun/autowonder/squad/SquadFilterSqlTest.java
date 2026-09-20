package com.aliyun.autowonder.squad;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The squad filter is derived (no schema change), so correctness lives entirely in the mapper SQL.
 * These assertions pin the two properties that are easy to regress silently: the filter must be
 * pushed into the paged query rather than applied to the page afterwards, and every derived join
 * must stay tenant-scoped.
 */
class SquadFilterSqlTest {

    @Test
    void agentListFiltersBySquadInsideThePagedQuery() throws Exception {
        String query = selectBody("mapping/AgentDao.xml", "list");

        assertTrue(query.contains("squadIds != null and squadIds.size() > 0"),
                "the squad filter must be optional so existing callers are unaffected");
        assertTrue(query.contains("SELECT sm.agent_id FROM squad_member sm"),
                "agents must be filtered through the squad_member link");
        assertTrue(query.contains("collection=\"squadIds\""),
                "every selected squad must widen the match, not just the first one");
        assertTrue(query.contains("sm.tenant_id = #{tenantId}"),
                "the squad subquery must stay inside the caller's workspace");
        assertTrue(query.contains("LIMIT #{offset}, #{limit}"),
                "pagination must apply after the squad filter so page sizes stay correct");
    }

    @Test
    void sdlcListDerivesSquadsFromOnlineAgentVersions() throws Exception {
        String query = selectBody("mapping/SdlcDao.xml", "list");

        assertTrue(query.contains("SELECT av.sdlc_id FROM agent_version av"),
                "an SDLC belongs to a squad through the agent versions that bind it");
        assertTrue(query.contains("a.online_version_id = av.id"),
                "only the published agent version counts, so a pending edit cannot reassign the SDLC");
        assertTrue(query.contains("JOIN squad_member sm ON sm.agent_id = a.id"),
                "the derived agent must itself be a squad member");
        assertTrue(query.contains("av.sdlc_id IS NOT NULL"),
                "versions without an SDLC binding must not produce a null IN-list entry");
        assertTrue(query.contains("av.tenant_id = #{tenantId} AND a.tenant_id = #{tenantId} AND sm.tenant_id = #{tenantId}"),
                "all three derived tables must be tenant-scoped");
        assertTrue(query.contains("LIMIT #{offset}, #{limit}"),
                "pagination must apply after the squad filter");
    }

    @Test
    void executorListAllFiltersByTheOwningAgentsSquads() throws Exception {
        String query = selectBody("mapping/ExecutorDao.xml", "listAll");

        assertTrue(query.contains("e.agent_id IN ("),
                "an executor follows its owning agent's squads");
        assertTrue(query.contains("SELECT sm.agent_id FROM squad_member sm"),
                "the executor filter must resolve through squad_member");
        assertTrue(query.contains("sm.tenant_id = #{tenantId} AND sm.squad_id IN"),
                "the squad subquery must stay inside the caller's workspace");
        assertTrue(query.contains("squadIds != null and squadIds.size() > 0"),
                "the squad filter must be optional");
    }

    @Test
    void executorListByAgentIdsIsTenantScoped() throws Exception {
        String query = selectBody("mapping/ExecutorDao.xml", "listByAgentIds");

        assertTrue(query.contains("e.tenant_id = #{tenantId} AND e.is_deleted = 0 AND e.agent_id IN"),
                "batch executor lookup must stay tenant-scoped and skip soft-deleted rows");
        assertTrue(query.contains("collection=\"agentIds\""),
                "squad executors must be fetched in one query, not one per agent");
    }

    @Test
    void squadMemberListByAgentIdsIsDeterministic() throws Exception {
        String query = selectBody("mapping/SquadMemberDao.xml", "listByAgentIds");

        assertTrue(query.contains("tenant_id = #{tenantId} AND agent_id IN"),
                "batch membership lookup must stay tenant-scoped");
        assertTrue(query.contains("ORDER BY agent_id, squad_id"),
                "a stable order keeps squadIds and squadNames index-aligned across calls");
    }

    @Test
    void squadListByIdsSkipsSoftDeletedSquads() throws Exception {
        String query = selectBody("mapping/SquadDao.xml", "listByIds");

        assertTrue(query.contains("is_deleted = 0 AND id IN"),
                "a deleted squad must not lend its name to a resource");
        assertTrue(query.contains("collection=\"ids\""),
                "squad names must be resolved in one query");
    }

    @Test
    void agentVersionListOnlineBySdlcIdsIsTenantScopedAndDistinct() throws Exception {
        String query = selectBody("mapping/AgentVersionDao.xml", "listOnlineBySdlcIds");

        assertTrue(query.contains("SELECT DISTINCT av.sdlc_id, av.agent_id"),
                "an SDLC bound by several versions of the same agent must yield one pair");
        assertTrue(query.contains("JOIN agent a ON a.online_version_id = av.id"),
                "only the published version links an SDLC back to its agent");
        assertTrue(query.contains("a.tenant_id = #{tenantId} AND a.is_deleted = 0"),
                "the owning agent must be live and in the caller's workspace");
        assertTrue(query.contains("av.sdlc_id IN"),
                "the lookup must be batched over the requested SDLC ids");
    }

    @Test
    void squadFilterSubqueriesSkipSoftDeletedSquads() throws Exception {
        // A soft-deleted squad keeps its squad_member rows, so without this join its id stays
        // filterable while SquadDao.listByIds refuses to lend it a name: filterable but untagged.
        for (String[] site : List.of(
                new String[]{"mapping/AgentDao.xml", "list"},
                new String[]{"mapping/SdlcDao.xml", "list"},
                new String[]{"mapping/ExecutorDao.xml", "listAll"})) {
            assertTrue(selectBody(site[0], site[1]).contains("JOIN squad s ON s.id = sm.squad_id AND s.is_deleted = 0"),
                    site[0] + "." + site[1] + " must not filter by a soft-deleted squad");
        }
    }

    @Test
    void batchInListsFallBackToNullInsteadOfAnEmptyInClause() throws Exception {
        // An empty collection renders "IN ()", a MySQL syntax error; IN (NULL) matches no row, which
        // is the right answer for a batch keyed by nothing.
        for (String[] site : List.of(
                new String[]{"mapping/ExecutorDao.xml", "listByAgentIds", "agentIds"},
                new String[]{"mapping/SquadDao.xml", "listByIds", "ids"},
                new String[]{"mapping/SquadMemberDao.xml", "listByAgentIds", "agentIds"},
                new String[]{"mapping/AgentVersionDao.xml", "listOnlineBySdlcIds", "sdlcIds"})) {
            String query = selectBody(site[0], site[1]);
            assertTrue(query.contains("<when test=\"" + site[2] + " != null and " + site[2] + ".size() > 0\">"),
                    site[0] + "." + site[1] + " must guard an empty " + site[2]);
            assertTrue(query.contains("<otherwise>(NULL)</otherwise>"),
                    site[0] + "." + site[1] + " must match no row for an empty " + site[2]);
        }
    }

    private String selectBody(String resource, String id) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Missing mapper " + resource);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String startTag = "<select id=\"" + id + "\"";
            int start = xml.indexOf(startTag);
            assertTrue(start >= 0, "Missing select " + id + " in " + resource);
            int bodyStart = xml.indexOf('>', start) + 1;
            int bodyEnd = xml.indexOf("</select>", bodyStart);
            assertTrue(bodyStart > 0 && bodyEnd > bodyStart, "Malformed select " + id);
            return xml.substring(bodyStart, bodyEnd).replaceAll("\\s+", " ").trim();
        }
    }
}
