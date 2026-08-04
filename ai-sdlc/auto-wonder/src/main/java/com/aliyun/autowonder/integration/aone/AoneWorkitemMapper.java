package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;

import java.util.Date;

public class AoneWorkitemMapper {

    public int toIssueTypeId(String workType) {
        if ("REQ".equalsIgnoreCase(workType)) return 9;
        if ("BUG".equalsIgnoreCase(workType)) return 6;
        return 8; // TASK
    }

    public ExternalWorkitemDetail toDetail(JSONObject issue) {
        ExternalWorkitemDetail detail = new ExternalWorkitemDetail();
        detail.setExternalId(str(issue, "id"));
        detail.setExternalProjectId(str(issue, "akProjectId"));
        detail.setExternalIssueTypeId(firstNonBlank(str(issue, "issueTypeId"), str(issue, "issueTypeID")));
        detail.setWorkType(toWorkType(str(issue, "stamp")));
        detail.setTitle(str(issue, "subject"));
        detail.setContentMd(firstNonBlank(str(issue, "description"), str(issue, "content")));
        detail.setStatusId(firstNonBlank(str(issue, "statusId"), str(issue, "statusID")));
        detail.setStatusName(str(issue, "status"));
        detail.setPriority(toPriority(str(issue, "priorityId"), str(issue, "priority")));
        detail.setAssigneeStaffId(firstNonBlank(str(issue, "assignedToStaffId"), str(issue, "assignToStaffId")));
        detail.setAuthorStaffId(firstNonBlank(str(issue, "userStaffId"), str(issue, "author")));
        detail.setUpdatedAt(date(firstNonBlank(str(issue, "modifiedAt"), str(issue, "gmtModified"), str(issue, "updateStatusAt"))));
        detail.setCreatedAt(date(str(issue, "createdAt")));
        detail.setRawJson(issue.toJSONString());
        return detail;
    }

    public String toWorkType(String stamp) {
        if ("Req".equalsIgnoreCase(stamp)) {
            return "REQ";
        }
        if ("Bug".equalsIgnoreCase(stamp)) {
            return "BUG";
        }
        if ("Task".equalsIgnoreCase(stamp)) {
            return "TASK";
        }
        return "TASK";
    }

    private Integer toPriority(String priorityId, String priorityName) {
        if ("94".equals(priorityId) || "Urgent".equalsIgnoreCase(priorityName)) {
            return 0;
        }
        if ("95".equals(priorityId) || "High".equalsIgnoreCase(priorityName)) {
            return 1;
        }
        if ("97".equals(priorityId) || "Low".equalsIgnoreCase(priorityName)) {
            return 3;
        }
        return 2;
    }

    private Date date(String millis) {
        if (millis == null || millis.isBlank()) {
            return null;
        }
        try {
            return new Date(Long.parseLong(millis));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String str(JSONObject object, String key) {
        Object value = object.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
