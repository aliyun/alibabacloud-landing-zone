package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AoneWorkitemMapperTest {

    @Test
    void mapsAoneIssueToExternalWorkitemDetail() {
        JSONObject issue = new JSONObject();
        issue.put("id", 84189105L);
        issue.put("akProjectId", "2161074");
        issue.put("stamp", "Req");
        issue.put("subject", "需求标题");
        issue.put("description", "需求正文");
        issue.put("statusId", 100005);
        issue.put("status", "Open");
        issue.put("priorityId", "95");
        issue.put("assignedToStaffId", "12345");
        issue.put("modifiedAt", 1720680000000L);

        ExternalWorkitemDetail detail =
                new AoneWorkitemMapper("https://aone.example.com/").toDetail(issue);

        assertEquals("84189105", detail.getExternalId());
        assertEquals("2161074", detail.getExternalProjectId());
        assertEquals("REQ", detail.getWorkType());
        assertEquals("需求标题", detail.getTitle());
        assertEquals("需求正文", detail.getContentMd());
        assertEquals("Open", detail.getStatusName());
        assertEquals("100005", detail.getStatusId());
        assertEquals(1, detail.getPriority());
        assertEquals("12345", detail.getAssigneeStaffId());
        assertEquals("https://aone.example.com/v2/project/2161074/req/84189105",
                detail.getExternalUrl());
    }

    @Test
    void omitsDeepLinkWhenNoWebBaseUrlConfigured() {
        JSONObject issue = new JSONObject();
        issue.put("id", 84189105L);
        issue.put("akProjectId", 2161074L);
        issue.put("stamp", "Req");

        ExternalWorkitemDetail detail = new AoneWorkitemMapper().toDetail(issue);

        assertNull(detail.getExternalUrl());
    }

    @Test
    void mapsBugAndTaskTypes() {
        JSONObject bug = new JSONObject();
        bug.put("id", 1);
        bug.put("stamp", "Bug");
        JSONObject task = new JSONObject();
        task.put("id", 2);
        task.put("stamp", "Task");

        AoneWorkitemMapper mapper = new AoneWorkitemMapper();

        assertEquals("BUG", mapper.toDetail(bug).getWorkType());
        assertEquals("TASK", mapper.toDetail(task).getWorkType());
    }

    @Test
    void mapsIdentityAndProviderDefinedRelationsWithoutChangingTheCommonSchema() {
        JSONObject issue = new JSONObject();
        issue.put("id", 84189105L);
        issue.put("stamp", "Req");
        issue.put("userStaffId", "10001");
        issue.put("userName", "需求提出人");
        issue.put("assignedToStaffId", "10002");
        issue.put("assignedToName", "业务负责人");
        issue.put("webUrl", "https://project.aone.alibaba-inc.com/issue/84189105");

        JSONArray participants = new JSONArray();
        JSONObject collaborator = new JSONObject();
        collaborator.put("staffId", "10003");
        collaborator.put("name", "协作者");
        JSONObject duplicateCollaborator = new JSONObject();
        duplicateCollaborator.put("staffId", "10003");
        duplicateCollaborator.put("name", "重复协作者");
        participants.add(collaborator);
        participants.add(duplicateCollaborator);
        issue.put("participantList", participants);

        ExternalWorkitemDetail detail = new AoneWorkitemMapper().toDetail(issue);

        assertNotNull(detail.getReporter());
        assertEquals("10001", detail.getReporter().getSubjectId());
        assertEquals("需求提出人", detail.getReporter().getDisplayName());
        assertEquals("10002", detail.getBusinessOwner().getSubjectId());
        assertEquals("https://project.aone.alibaba-inc.com/issue/84189105", detail.getExternalUrl());
        assertEquals("ACTIVE", detail.getSourceLifecycle());
        assertEquals(1, detail.getPrincipalRelations().size());
        assertEquals("participants", detail.getPrincipalRelations().get(0).getSourceKey());
        assertEquals("参与者", detail.getPrincipalRelations().get(0).getDisplayName());
        assertEquals(1, detail.getPrincipalRelations().get(0).getPrincipals().size());
        assertEquals("10003", detail.getPrincipalRelations().get(0).getPrincipals().get(0).getSubjectId());
    }

    @Test
    void mapsActualAoneSearchFieldsForReporterTimestampAndRelations() {
        JSONObject issue = new JSONObject();
        issue.put("id", 85051569L);
        issue.put("stamp", "Req");
        issue.put("userStaffId", "440501");
        issue.put("user", "煊童");
        issue.put("updatedAt", 1785828214000L);

        JSONArray participants = new JSONArray();
        participants.add("WB711544");
        issue.put("participantStaffIds", participants);
        JSONArray trackers = new JSONArray();
        trackers.add("WORKER_1783582458263");
        issue.put("trackers", trackers);

        ExternalWorkitemDetail detail = new AoneWorkitemMapper().toDetail(issue);

        assertEquals("煊童", detail.getReporter().getDisplayName());
        assertEquals(1785828214000L, detail.getUpdatedAt().getTime());
        assertEquals(2, detail.getPrincipalRelations().size());
        assertEquals("WB711544", detail.getPrincipalRelations().get(0).getPrincipals().get(0).getSubjectId());
        assertEquals("WORKER_1783582458263", detail.getPrincipalRelations().get(1).getPrincipals().get(0).getSubjectId());
    }
}
