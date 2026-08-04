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
}
