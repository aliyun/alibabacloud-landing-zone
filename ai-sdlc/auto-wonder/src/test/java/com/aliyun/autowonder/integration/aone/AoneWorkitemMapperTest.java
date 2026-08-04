package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        ExternalWorkitemDetail detail = new AoneWorkitemMapper().toDetail(issue);

        assertEquals("84189105", detail.getExternalId());
        assertEquals("2161074", detail.getExternalProjectId());
        assertEquals("REQ", detail.getWorkType());
        assertEquals("需求标题", detail.getTitle());
        assertEquals("需求正文", detail.getContentMd());
        assertEquals("Open", detail.getStatusName());
        assertEquals("100005", detail.getStatusId());
        assertEquals(1, detail.getPriority());
        assertEquals("12345", detail.getAssigneeStaffId());
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
}
