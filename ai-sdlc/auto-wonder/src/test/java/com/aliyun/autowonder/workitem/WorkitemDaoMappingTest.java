package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemDaoMappingTest {

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
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<sql id=\"listFilter\">"));
        assertTrue(xml.contains("w.tenant_id = #{tenantId}"));
        assertTrue(xml.contains("<select id=\"count\" resultType=\"long\">"));
        assertTrue(xml.contains("w.assignee_type = 'HUMAN'"));
        assertTrue(xml.contains("w.assignee_ref = #{currentUserId}"));
        assertTrue(xml.contains("d.status = 'SUCCEEDED'"));
        assertTrue(xml.contains("SELECT MAX(d2.id) FROM dispatch d2"));
        assertTrue(xml.contains("UPPER(COALESCE(sn.category, '')) = 'DONE'"));
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
    void statusCategoryFilterIsAppliedInsideListFilterBeforePagination() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<when test=\"statusCategory == 'IN_PROGRESS'\">"),
                "listFilter should support IN_PROGRESS status category");
        assertTrue(xml.contains("<when test=\"statusCategory == 'DONE'\">"),
                "listFilter should support DONE status category");
        assertTrue(xml.contains("<when test=\"statusCategory == 'PENDING_DECISION'\">"),
                "listFilter should support PENDING_DECISION status category");
        assertTrue(xml.contains("<when test=\"statusCategory == 'NEW'\">"),
                "listFilter should support NEW status category");

        int filterEnd = xml.indexOf("</sql>", xml.indexOf("<sql id=\"listFilter\">"));
        int statusCategoryIdx = xml.indexOf("statusCategory == 'DONE'");
        assertTrue(statusCategoryIdx > xml.indexOf("<sql id=\"listFilter\">") && statusCategoryIdx < filterEnd,
                "status category filter must live inside listFilter so LIMIT pagination applies after it");
    }

    @Test
    void statusCategoryInProgressExcludesDoneAndPendingDecision() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int start = xml.indexOf("<when test=\"statusCategory == 'IN_PROGRESS'\">");
        int end = xml.indexOf("</when>", start);
        String block = xml.substring(start, end);
        assertTrue(block.contains("AND NOT <include refid=\"statusNameDone\"/>"),
                "IN_PROGRESS must exclude done workitems");
        assertTrue(block.contains("AND NOT <include refid=\"statusPendingDecision\"/>"),
                "IN_PROGRESS must exclude pending-decision workitems, matching kanban column semantics");
        assertTrue(block.contains("AND <include refid=\"statusNameInProgress\"/>"),
                "IN_PROGRESS must match in-progress status names");
    }

    @Test
    void statusNameDoneRecognizesAoneFixedWithoutUsingStatusCategory() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int start = xml.indexOf("<sql id=\"statusNameDone\">");
        int end = xml.indexOf("</sql>", start);
        String block = xml.substring(start, end);
        assertTrue(block.contains("UPPER(snc.name) LIKE '%FIXED%'"),
                "Aone Fixed status names should be included in the DONE kanban column");
        assertTrue(!block.contains("category"),
                "DONE kanban classification should remain status-name based");
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
