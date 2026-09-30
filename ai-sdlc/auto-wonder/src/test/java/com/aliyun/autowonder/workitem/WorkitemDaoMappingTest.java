package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemDaoMappingTest {

    @Test
    void watchedScopeAppliesToBothListAndCountOnlyWhenSelected() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setDatabaseId("autowonder-source-aware");
        try (var input = getClass().getResourceAsStream("/mapping/WorkitemDao.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration,
                    "mapping/WorkitemDao.xml", configuration.getSqlFragments()).parse();
        }
        var params = new java.util.HashMap<String, Object>();
        params.put("tenantId", 7L);
        params.put("currentUserId", 42L);
        params.put("pendingDecisionOnly", false);
        for (String statement : java.util.List.of("list", "count")) {
            var mapped = configuration.getMappedStatement(WorkitemDao.class.getName() + "." + statement);
            params.put("mineScope", "WATCHED");
            String sql = mapped.getBoundSql(params).getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("SELECT 1 FROM workitem_watcher ww"));
            assertTrue(sql.contains("ww.tenant_id = w.tenant_id"));
            assertTrue(sql.contains("ww.workitem_id = w.id"));
            assertTrue(sql.contains("ww.user_id = ?"));
            assertTrue(sql.contains("w.is_deleted = 0"));
            params.remove("mineScope");
            org.junit.jupiter.api.Assertions.assertFalse(
                    mapped.getBoundSql(params).getSql().contains("workitem_watcher"));
        }
    }

    @Test
    void listOrdersWorkitemsByCreateTimeDescThenIdDesc() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("ORDER BY w.gmt_create DESC, w.id DESC"),
                "workitem list should show newest workitems first by create time, then id");
    }

    @Test
    void listAndCountShareTenantScopedPendingDecisionFilter() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setDatabaseId("autowonder-source-aware");
        try (var input = getClass().getResourceAsStream("/mapping/WorkitemDao.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration,
                    "mapping/WorkitemDao.xml", configuration.getSqlFragments()).parse();
        }

        assertTrue(xmlText().contains("<sql id=\"listFilter\">"));
        assertTrue(xmlText().contains("w.tenant_id = #{tenantId}"));
        assertTrue(xmlText().contains("<select id=\"count\" resultType=\"long\">"));

        var params = new java.util.HashMap<String, Object>();
        params.put("tenantId", 7L);
        params.put("currentUserId", 42L);
        params.put("pendingDecisionOnly", true);
        for (String statement : java.util.List.of("list", "count")) {
            var mapped = configuration.getMappedStatement(WorkitemDao.class.getName() + "." + statement);
            String sql = mapped.getBoundSql(params).getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("<include refid=\"listFilter\"/>") || sql.contains("CASE"),
                    "list and count must share the same filter");
            assertTrue(sql.contains("w.assignee_type = 'HUMAN'"),
                    "pending decision requires a human assignee");
            assertTrue(sql.contains("w.assignee_ref = ?"),
                    "pending decision requires the current user as assignee");
            assertTrue(sql.contains("IN ('INIT', 'IN_PROGRESS')"),
                    "pending decision requires an unfinished node category");
            assertTrue(sql.contains("d.source_type = 'WORKITEM'"),
                    "pending decision requires a historically started workitem dispatch");
            assertTrue(sql.contains("'SUCCEEDED'"),
                    "dispatchStarted must recognize terminal successes as started dispatches");
            assertTrue(sql.contains("'DISPATCHED'"),
                    "dispatchActive must list active states");
            assertFalse(sql.contains("'PACKAGING'"),
                    "pending decision excludes workitems with an active (queued/packaging) dispatch");
        }
    }

    private String xmlText() throws Exception {
        return new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void numericKeywordAddsBothTitleLikeAndIdMatch() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("w.title LIKE CONCAT('%', #{keyword}, '%')"),
                "keyword filter should include title LIKE match");
        assertTrue(xml.contains("w.id = #{keywordId}"),
                "numeric keyword should also match workitem id exactly");
    }

    @Test
    void nonNumericKeywordAddsOnlyTitleLike() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("w.title LIKE CONCAT('%', #{keyword}, '%')"),
                "keyword filter should include title LIKE match");
        assertTrue(xml.contains("<if test=\"keywordId != null\">"),
                "id match should be guarded by keywordId null check so non-numeric keywords skip it");
    }

    @Test
    void emptyKeywordAddsNoKeywordCondition() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<if test=\"keyword != null and keyword != ''\">"),
                "keyword filter should be guarded by non-null/non-empty check");
    }

    @Test
    void mineScopeCreatedFiltersByCreatorId() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<if test=\"mineScope == 'CREATED'\">"),
                "listFilter should have a mineScope CREATED branch");
        assertTrue(xml.contains("AND w.creator_id = #{currentUserId}"),
                "CREATED scope should filter by creator_id");
    }

    @Test
    void mineScopeAssignedFiltersByAssigneeWithoutStatusExclusion() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<if test=\"mineScope == 'ASSIGNED'\">"),
                "listFilter should have a mineScope ASSIGNED branch");
        assertTrue(xml.contains("AND w.assignee_type = 'HUMAN' AND w.assignee_ref = #{currentUserId}"),
                "ASSIGNED scope should filter by assignee_type=HUMAN and assignee_ref=currentUserId");
    }

    @Test
    void mineScopeConditionsAreIndependentOfPendingDecision() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int createdIdx = xml.indexOf("mineScope == 'CREATED'");
        int pendingIdx = xml.indexOf("pendingDecisionOnly");
        assertTrue(createdIdx > pendingIdx,
                "mineScope conditions should come after pendingDecisionOnly so they are independent filters");

        String assignedBlock = xml.substring(xml.indexOf("mineScope == 'ASSIGNED'"), xml.indexOf("keyword != null"));
        assertTrue(!assignedBlock.contains("sn.category") && !assignedBlock.contains("DONE"),
                "ASSIGNED scope must not include status exclusion clauses");
    }

    @Test
    void statusCategoryFilterComparesUnifiedKanbanCategoryInsideListFilter() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<if test=\"statusCategory != null\">"),
                "status category filter should be guarded by a null check");
        assertTrue(xml.contains("AND <include refid=\"kanbanCategoryExpr\"/> = #{statusCategory}"),
                "status category filter should compare the unified kanban classification expression");

        int filterStart = xml.indexOf("<sql id=\"listFilter\">");
        int filterEnd = xml.indexOf("</sql>", filterStart);
        int categoryIdx = xml.indexOf("kanbanCategoryExpr\"/> = #{statusCategory}");
        assertTrue(categoryIdx > filterStart && categoryIdx < filterEnd,
                "status category filter must live inside listFilter so LIMIT pagination applies after it");
        assertTrue(xml.contains("<include refid=\"listFilter\"/>"),
                "list and count should both include listFilter");
    }

    @Test
    void kanbanCategoryExprMirrorsEvaluatorPriority() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int start = xml.indexOf("<sql id=\"kanbanCategoryExpr\">");
        assertTrue(start > 0, "kanbanCategoryExpr must exist");
        String block = xml.substring(start, xml.indexOf("</sql>", start));

        int canceledIdx = block.indexOf("= 'CANCELED')");
        int doneIdx = block.indexOf("= 'DONE')");
        int activeIdx = block.indexOf("WHEN <include refid=\"dispatchActive\"/>");
        int pendingIdx = block.indexOf("<include refid=\"dispatchStarted\"/>");
        int inProgressNodeIdx = block.lastIndexOf("= 'IN_PROGRESS')");
        assertTrue(canceledIdx >= 0 && doneIdx > canceledIdx && pendingIdx > doneIdx && activeIdx > pendingIdx,
                "CASE priority must be CANCELED > DONE > pending decision > active dispatch");
        assertTrue(block.contains("w.assignee_type = 'HUMAN'"),
                "pending decision requires a human assignee");
        assertTrue(block.contains("w.assignee_ref IS NOT NULL"),
                "pending decision requires a resolved assignee ref");
        assertTrue(block.contains("IN ('INIT', 'IN_PROGRESS')"),
                "pending decision only applies to unfinished node categories");
        assertTrue(pendingIdx < inProgressNodeIdx,
                "pending decision outranks the plain IN_PROGRESS node branch");
        assertTrue(block.contains("ELSE 'NEW'"),
                "everything else falls back to NEW");
    }

    @Test
    void pendingDecisionQueriesIncludeHumanHandoffsAndIdleExternalOwners() throws Exception {
        for (String databaseId : java.util.List.of("autowonder-legacy", "autowonder-source-aware")) {
            var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:kanban-" + databaseId + ";MODE=MySQL", "sa", "");
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE status_node (id BIGINT PRIMARY KEY, category VARCHAR(20))");
                statement.execute("CREATE TABLE workitem (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                        + "status_node_id BIGINT, assignee_type VARCHAR(20), assignee_ref BIGINT, "
                        + "is_deleted INT DEFAULT 0, gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                statement.execute("CREATE TABLE dispatch (tenant_id BIGINT, workitem_id BIGINT, "
                        + "source_type VARCHAR(32) DEFAULT 'WORKITEM', status VARCHAR(20), is_deleted INT DEFAULT 0)");
                statement.execute("INSERT INTO status_node VALUES (1,'INIT'),(2,'IN_PROGRESS'),(3,'DONE'),(4,'CANCELED')");
                statement.execute("""
                        INSERT INTO workitem (id,tenant_id,status_node_id,assignee_type,assignee_ref) VALUES
                        (1,7,1,'HUMAN',42),(2,7,2,'EXTERNAL',0),(3,7,2,'EXTERNAL',0),
                        (4,7,2,'AGENT',42),(5,7,1,'EXTERNAL',0),(6,7,2,'HUMAN',NULL),
                        (7,7,3,'HUMAN',42),(8,7,4,'EXTERNAL',0),(9,8,1,'HUMAN',42),
                        (10,7,2,'EXTERNAL',42),(11,7,1,'EXTERNAL',0),(12,7,1,'HUMAN',42)
                        """);
                statement.execute("""
                        INSERT INTO dispatch (tenant_id,workitem_id,status) VALUES
                        (7,1,'PAUSED'),(7,1,'SUCCEEDED'),(7,2,'SUCCEEDED'),(7,3,'RUNNING'),
                        (7,4,'SUCCEEDED'),(7,6,'SUCCEEDED'),(7,7,'PAUSED'),(7,8,'SUCCEEDED'),
                        (8,9,'SUCCEEDED'),(7,10,'SUCCEEDED'),(7,11,'SUCCEEDED'),(8,12,'SUCCEEDED')
                        """);
                statement.execute("INSERT INTO dispatch VALUES (7,11,'SCHEDULED_TASK_RUN','RUNNING',0)");

                var configuration = new org.apache.ibatis.session.Configuration();
                configuration.setDatabaseId(databaseId);
                configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds));
                try (var input = getClass().getResourceAsStream("/mapping/WorkitemDao.xml")) {
                    new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration,
                            "mapping/WorkitemDao.xml", configuration.getSqlFragments()).parse();
                }
                try (var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                        .build(configuration).openSession()) {
                    var params = new java.util.HashMap<String, Object>();
                    params.put("tenantId", 7L);
                    params.put("currentUserId", 42L);
                    params.put("pendingDecisionOnly", false);
                    params.put("offset", 0);
                    params.put("limit", 100);
                    boolean sourceAware = databaseId.equals("autowonder-source-aware");
                    var expected = java.util.Map.of(
                            "PENDING_DECISION", sourceAware ? java.util.List.of(1L,2L,10L,11L) : java.util.List.of(1L,2L,10L),
                            "IN_PROGRESS", sourceAware ? java.util.List.of(3L,4L,6L) : java.util.List.of(3L,4L,6L,11L),
                            "NEW", java.util.List.of(5L,12L), "DONE", java.util.List.of(7L), "CANCELED", java.util.List.of(8L));
                    for (var entry : expected.entrySet()) {
                        params.put("statusCategory", entry.getKey());
                        java.util.List<WorkitemDO> rows = session.selectList(WorkitemDao.class.getName() + ".list", params);
                        org.junit.jupiter.api.Assertions.assertEquals(entry.getValue(), rows.stream()
                                .map(WorkitemDO::getId).sorted().toList(), databaseId + " " + entry.getKey());
                        org.junit.jupiter.api.Assertions.assertEquals((long) entry.getValue().size(),
                                (long) session.selectOne(WorkitemDao.class.getName() + ".count", params));
                    }
                    params.remove("statusCategory");
                    params.put("pendingDecisionOnly", true);
                    java.util.List<WorkitemDO> mine = session.selectList(WorkitemDao.class.getName() + ".list", params);
                    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(1L),
                            mine.stream().map(WorkitemDO::getId).toList(), "external refs must not match a local user");
                }
            }
        }
    }

    @Test
    void mapperDropsStatusNameKeywordClassification() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(!xml.contains("statusNameDone") && !xml.contains("statusNameInProgress")
                && !xml.contains("statusPendingDecision") && !xml.contains("notDoneNodeFilter"),
                "status-name keyword fragments must be removed from the mapper");
        assertTrue(!xml.contains("LIKE '%FIXED%'") && !xml.contains("LIKE '%DONE%'"),
                "status names are display-only and must not drive SQL classification");
    }

    @Test
    void statusCategoryFilterBindsUnifiedCategoryInBothListAndCount() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setDatabaseId("autowonder-source-aware");
        try (var input = getClass().getResourceAsStream("/mapping/WorkitemDao.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration,
                    "mapping/WorkitemDao.xml", configuration.getSqlFragments()).parse();
        }
        var params = new java.util.HashMap<String, Object>();
        params.put("tenantId", 7L);
        params.put("currentUserId", 42L);
        params.put("pendingDecisionOnly", false);
        for (String statement : java.util.List.of("list", "count")) {
            var mapped = configuration.getMappedStatement(WorkitemDao.class.getName() + "." + statement);
            params.put("statusCategory", "CANCELED");
            String sql = mapped.getBoundSql(params).getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("CASE"), "filtered queries must embed the unified classification CASE");
            assertTrue(sql.contains("THEN 'CANCELED'"),
                    "CANCELED workitems must be reachable through the explicit filter");
            assertTrue(sql.contains("d.source_type = 'WORKITEM'"),
                    "source-aware dispatch facts must scope to workitem deliveries");
            params.remove("statusCategory");
            org.junit.jupiter.api.Assertions.assertFalse(
                    mapped.getBoundSql(params).getSql().contains("CASE"),
                    "unfiltered queries must not pay for the classification expression");
        }
    }

    @Test
    void tagFilterUsesJsonContainsInsideListFilter() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<if test=\"tag != null and tag != ''\">"),
                "tag filter should be guarded by non-null/non-empty check");
        assertTrue(xml.contains("AND JSON_CONTAINS(w.tags, JSON_QUOTE(#{tag}))"),
                "tag filter should match a single JSON array element");

        int filterEnd = xml.indexOf("</sql>", xml.indexOf("<sql id=\"listFilter\">"));
        int tagIdx = xml.indexOf("JSON_CONTAINS(w.tags");
        assertTrue(tagIdx > xml.indexOf("<sql id=\"listFilter\">") && tagIdx < filterEnd,
                "tag filter must live inside listFilter so list and count share it");
    }

    @Test
    void scheduledStartFilterLivesInsideSharedListFilterWithoutOtherwiseBranch() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<when test=\"scheduledStart == 'ALL'\">"),
                "ALL should return both pending and triggered scheduled workitems");
        assertTrue(xml.contains("AND (w.scheduled_start_at IS NOT NULL OR w.scheduled_start_triggered_at IS NOT NULL)"),
                "ALL must match either the pending or the already-triggered schedule column");
        assertTrue(xml.contains("<when test=\"scheduledStart == 'PENDING'\">"),
                "PENDING should return only not-yet-fired schedules");
        assertTrue(xml.contains("<when test=\"scheduledStart == 'TRIGGERED'\">"),
                "TRIGGERED should return only fired schedules");
        assertTrue(xml.contains("AND w.scheduled_start_triggered_at IS NOT NULL"),
                "TRIGGERED must match the trigger timestamp written by fireScheduledStartAt");

        int filterStart = xml.indexOf("<sql id=\"listFilter\">");
        int filterEnd = xml.indexOf("</sql>", filterStart);
        int allIdx = xml.indexOf("scheduledStart == 'ALL'");
        assertTrue(allIdx > filterStart && allIdx < filterEnd,
                "scheduled start filter must live inside listFilter so list and count share the same condition");
        assertTrue(xml.contains("<include refid=\"listFilter\"/>"),
                "list and count should both include listFilter");

        String chooseBlock = xml.substring(xml.lastIndexOf("<choose>", allIdx), xml.indexOf("</choose>", allIdx));
        assertTrue(!chooseBlock.contains("<otherwise>"),
                "no otherwise branch so an absent or illegal scheduledStart adds no condition and keeps existing lists unchanged");
    }

    @Test
    void findScheduledDueOnlyMatchesAgentWorkitemsWithSdlc() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int start = xml.indexOf("<select id=\"findScheduledDue\"");
        assertTrue(start > 0, "findScheduledDue select must exist");
        String block = xml.substring(start, xml.indexOf("</select>", start));
        assertTrue(block.contains("scheduled_start_at &lt;= #{now}"),
                "scan should pick workitems whose planned start time has arrived");
        assertTrue(block.contains("assignee_type = 'AGENT'"),
                "only agent-assigned workitems have deferred delivery to fire");
        assertTrue(block.contains("sdlc_id IS NOT NULL"),
                "only SDLC-bound workitems can be dispatched");
        assertTrue(block.contains("ORDER BY scheduled_start_at ASC"),
                "earliest scheduled workitems should fire first");
        assertTrue(block.contains("LIMIT #{limit}"),
                "scan batch must be bounded");
    }

    @Test
    void scheduledStartAndTagsUpdatesUseVersionCas() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int clearStart = xml.indexOf("<update id=\"clearScheduledStartAt\">");
        assertTrue(clearStart > 0, "clearScheduledStartAt update must exist");
        String clear = xml.substring(clearStart, xml.indexOf("</update>", clearStart));
        assertTrue(clear.contains("SET scheduled_start_at = NULL, version = version + 1"),
                "clear must null the schedule and bump version");
        assertTrue(clear.contains("WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{version}"),
                "clear must be CAS-guarded so only one scanner node wins");
        assertTrue(clear.contains("AND scheduled_start_at IS NOT NULL"),
                "clear must be a no-op once the schedule is already gone");

        int updateStart = xml.indexOf("<update id=\"updateScheduledStartAt\">");
        assertTrue(updateStart > 0, "updateScheduledStartAt update must exist");
        String update = xml.substring(updateStart, xml.indexOf("</update>", updateStart));
        assertTrue(update.contains("SET scheduled_start_at = #{scheduledStartAt}"),
                "update must write the new planned start time");
        assertTrue(update.contains("WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{version}"),
                "update must be CAS-guarded");

        int tagsStart = xml.indexOf("<update id=\"updateTags\">");
        assertTrue(tagsStart > 0, "updateTags update must exist");
        String tags = xml.substring(tagsStart, xml.indexOf("</update>", tagsStart));
        assertTrue(tags.contains("SET tags = #{tags}"),
                "updateTags must replace the whole tags JSON");
        assertTrue(tags.contains("WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{version}"),
                "updateTags must be CAS-guarded");
    }

    @Test
    void fireScheduledStartAtStampsTriggeredTimeUnderCas() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int fireStart = xml.indexOf("<update id=\"fireScheduledStartAt\">");
        assertTrue(fireStart > 0, "fireScheduledStartAt update must exist");
        String fire = xml.substring(fireStart, xml.indexOf("</update>", fireStart));
        assertTrue(fire.contains("scheduled_start_at = NULL"),
                "fire must clear the planned start");
        assertTrue(fire.contains("scheduled_start_triggered_at = NOW(3)"),
                "fire must stamp the actual trigger time so the scheduled badge survives triggering");
        assertTrue(fire.contains("WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{version}"),
                "fire must be CAS-guarded so only one scanner node wins");
        assertTrue(fire.contains("AND scheduled_start_at IS NOT NULL"),
                "fire must be a no-op once the schedule is already gone");
    }
}
