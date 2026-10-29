package com.aliyun.autowonder.spacedashboard;

import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper contract for the space dashboard: the predicates that decide who is counted and how the
 * time window is applied cannot be observed through a mocked DAO, so they are asserted on the
 * mapper XML itself.
 *
 * <p>Each fact below is a business rule from the requirement spec: platform built-in agents must
 * never inflate the agent count, the access level is read from {@code workspace_role} rather than
 * the legacy {@code org_member.access_level} column, completion is measured by the latest
 * STATUS_CHANGE into a DONE node, and both AI usage tables feed the credits total.
 */
class SpaceDashboardDaoSqlTest {

    private static final String XML = "/mapping/SpaceDashboardDao.xml";

    @Test
    void theScopeReadsTheRoleTableAndExcludesDisabledOrDeletedWorkspaces() throws Exception {
        String sql = statement("listScopedWorkspaces");

        assertTrue(sql.contains("FROM `org` o"), "scope must start from org, got: " + sql);
        assertTrue(sql.contains("o.is_deleted = 0"), "deleted workspaces must be excluded, got: " + sql);
        assertTrue(sql.contains("o.status = 0"), "disabled workspaces must be excluded, got: " + sql);
        assertTrue(sql.contains("o.owner_id = #{userId}"), "owners must stay in scope, got: " + sql);
        assertTrue(sql.contains("r.access_level = 'ADMIN'"),
                "the ADMIN level must come from the joined role, got: " + sql);
        assertTrue(sql.contains("JOIN workspace_role r ON r.id = m.role_id")
                        || sql.contains("JOIN workspace_role r\n               ON r.id = m.role_id"),
                "the role join must be keyed on org_member.role_id, got: " + sql);
        // org_member.access_level is a migration leftover; reading it would disagree with AuthFilter.
        assertFalse(sql.contains("m.access_level"),
                "scope must not read the legacy member access_level column, got: " + sql);
        assertTrue(sql.contains("<if test=\"!systemAdmin\">"),
                "platform admins must skip the owner/ADMIN predicate, got: " + sql);
    }

    @Test
    void agentCountSkipsPlatformBuiltInsAndDeletedAgents() throws Exception {
        String sql = statement("countAgents");

        assertTrue(sql.contains("FROM agent"), "got: " + sql);
        assertTrue(sql.contains("kind = 'STANDARD'"),
                "platform built-in agents must be excluded, got: " + sql);
        assertTrue(sql.contains("is_deleted = 0"), "got: " + sql);
        assertTrue(sql.contains("GROUP BY tenant_id"), "counts are grouped per workspace, got: " + sql);
    }

    @Test
    void squadCountExcludesDisbandedSquads() throws Exception {
        String sql = statement("countSquads");

        assertTrue(sql.contains("FROM squad"), "got: " + sql);
        assertTrue(sql.contains("status = 0"), "disbanded squads must be excluded, got: " + sql);
        assertTrue(sql.contains("is_deleted = 0"), "got: " + sql);
    }

    @Test
    void humanCountDeduplicatesAcrossWorkspacesAndSkipsDeletedAccounts() throws Exception {
        String sql = statement("countDistinctHumans");

        assertTrue(sql.contains("COUNT(DISTINCT m.user_id)"),
                "one human in several workspaces must count once, got: " + sql);
        assertTrue(sql.contains("JOIN `user` u ON u.id = m.user_id AND u.is_deleted = 0"),
                "deleted accounts must be excluded, got: " + sql);
        assertTrue(sql.contains("m.status = 0"), "removed/pending members must be excluded, got: " + sql);
        assertTrue(sql.contains("m.is_deleted = 0"), "got: " + sql);
    }

    @Test
    void createdAndInProgressCountsUseTheCreationWindowWithAnExclusiveEnd() throws Exception {
        for (String id : List.of("countCreatedWorkitems", "countInProgressWorkitems")) {
            String sql = statement(id);
            assertTrue(sql.contains("w.gmt_create &gt;= #{start}"), id + " must be left-closed, got: " + sql);
            assertTrue(sql.contains("w.gmt_create &lt; #{end}"), id + " must be right-open, got: " + sql);
            assertTrue(sql.contains("w.is_deleted = 0"), "got: " + sql);
        }
        assertTrue(statement("countInProgressWorkitems")
                        .contains("sn.category IN ('INIT', 'IN_PROGRESS')"),
                "in-progress is derived from the status node category, not the response-expensive "
                        + "kanban expression");
    }

    @Test
    void completionIsMeasuredByTheLatestStatusChangeIntoADoneNode() throws Exception {
        String sql = statement("countCompletedWorkitems");

        assertTrue(sql.contains("sn.category = 'DONE'"),
                "only currently-done workitems count, got: " + sql);
        assertTrue(sql.contains("(SELECT MAX(e.gmt_create)"), "the latest completion event wins, got: " + sql);
        assertTrue(sql.contains("e.event_type = 'STATUS_CHANGE'"), "got: " + sql);
        assertTrue(sql.contains("done_node.code = e.to_val"), "events store the node code, got: " + sql);
        assertTrue(sql.contains("done_node.category = 'DONE'"), "got: " + sql);
        assertTrue(sql.contains("done_node.template_id = w.template_id"),
                "the node lookup must stay on the workitem's own template, got: " + sql);
        assertTrue(sql.contains("counted.done_at &gt;= #{start}")
                        && sql.contains("counted.done_at &lt; #{end}"),
                "completion must be filtered by the completion window, got: " + sql);
    }

    @Test
    void creditsSumBothUsageTablesAndTreatNullAsZero() throws Exception {
        String sql = statement("sumCredits");

        assertTrue(sql.contains("FROM dispatch_ai_usage"), "got: " + sql);
        assertTrue(sql.contains("FROM agent_conversation_usage"), "got: " + sql);
        assertTrue(sql.contains("UNION ALL"), "both usage sources must be unioned, got: " + sql);
        assertTrue(sql.contains("COALESCE(credits, 0)"), "null credits count as zero, got: " + sql);
        assertTrue(sql.contains("usage_at &gt;= #{start}") && sql.contains("usage_at &lt; #{end}"),
                "usage_at drives the cost window, got: " + sql);
    }

    @Test
    void everyDaoParameterIsNamedForTheMapper() throws Exception {
        assertParamNames("listScopedWorkspaces", "userId", "systemAdmin");
        assertParamNames("countAgents", "workspaceIds");
        assertParamNames("countSquads", "workspaceIds");
        assertParamNames("countDistinctHumans", "workspaceIds");
        assertParamNames("countCreatedWorkitems", "workspaceIds", "start", "end", "workTypes");
        assertParamNames("countCompletedWorkitems", "workspaceIds", "start", "end", "workTypes");
        assertParamNames("countInProgressWorkitems", "workspaceIds", "start", "end", "workTypes");
        assertParamNames("sumCredits", "workspaceIds", "start", "end");
    }

    private static void assertParamNames(String methodName, String... expected) throws Exception {
        Method method = Arrays.stream(SpaceDashboardDao.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        List<String> actual = Arrays.stream(method.getParameters())
                .map(SpaceDashboardDaoSqlTest::paramName)
                .toList();
        assertEquals(List.of(expected), actual, "SpaceDashboardDao." + methodName + " parameter names");
    }

    private static String paramName(Parameter parameter) {
        Param annotation = parameter.getAnnotation(Param.class);
        assertNotNull(annotation, "missing @Param on " + parameter);
        return annotation.value();
    }

    private static String statement(String id) throws Exception {
        String xml = mapperXml();
        String marker = "id=\"" + id + "\"";
        int idStart = xml.indexOf(marker);
        assertTrue(idStart >= 0, "missing mapper statement " + id);
        int end = xml.indexOf("</select>", idStart);
        assertTrue(end >= 0, "missing closing select for " + id);
        return xml.substring(idStart, end);
    }

    private static String mapperXml() throws Exception {
        try (var stream = SpaceDashboardDaoSqlTest.class.getResourceAsStream(XML)) {
            assertNotNull(stream, "missing mapper " + XML);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        }
    }
}
