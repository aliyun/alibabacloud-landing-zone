package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.aliyun.autowonder.integration.provider.ExternalPrincipalRef;
import com.aliyun.autowonder.integration.provider.ExternalPrincipalRelation;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AoneWorkitemMapper {

    private final String webBaseUrl;

    public AoneWorkitemMapper() {
        this(null);
    }

    public AoneWorkitemMapper(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank()
                ? null
                : webBaseUrl.trim().replaceAll("/+$", "");
    }

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
        detail.setExternalUrl(firstNonBlank(
                str(issue, "webUrl"),
                str(issue, "url"),
                aoneWebUrl(detail.getExternalProjectId(), detail.getWorkType(), detail.getExternalId())));
        detail.setSourceLifecycle(toLifecycle(issue));
        detail.setReporter(ExternalPrincipalRef.user(detail.getAuthorStaffId(),
                firstNonBlank(str(issue, "userName"), str(issue, "user"), str(issue, "authorName"), str(issue, "creatorName"))));
        detail.setBusinessOwner(ExternalPrincipalRef.user(detail.getAssigneeStaffId(),
                firstNonBlank(str(issue, "assignedTo"), str(issue, "assignedToName"), str(issue, "assigneeName"))));
        detail.setPrincipalRelations(principalRelations(issue));
        detail.setUpdatedAt(date(firstNonBlank(str(issue, "modifiedAt"), str(issue, "gmtModified"),
                str(issue, "updatedAt"), str(issue, "updateStatusAt"))));
        detail.setCreatedAt(date(str(issue, "createdAt")));
        detail.setRawJson(issue.toJSONString());
        return detail;
    }

    private List<ExternalPrincipalRelation> principalRelations(JSONObject issue) {
        List<ExternalPrincipalRelation> relations = new ArrayList<>();
        addRelation(relations, issue, "participants", "参与者",
                "participants", "participantList", "involvedUserList", "participantStaffIds");
        addRelation(relations, issue, "watchers", "关注者",
                "watchers", "watcherList", "subscriberList", "trackerStaffIds", "trackers");
        return relations;
    }

    private void addRelation(List<ExternalPrincipalRelation> relations, JSONObject issue,
                             String sourceKey, String displayName, String... sourceFields) {
        JSONArray source = firstArray(issue, sourceFields);
        if (source == null || source.isEmpty()) {
            return;
        }
        Map<String, ExternalPrincipalRef> principals = new LinkedHashMap<>();
        for (Object item : source) {
            ExternalPrincipalRef principal = principal(item);
            if (principal != null) {
                principals.putIfAbsent(principal.getSubjectId(), principal);
            }
        }
        if (principals.isEmpty()) {
            return;
        }
        ExternalPrincipalRelation relation = new ExternalPrincipalRelation();
        relation.setSourceKey(sourceKey);
        relation.setDisplayName(displayName);
        relation.setPrincipals(new ArrayList<>(principals.values()));
        relations.add(relation);
    }

    private JSONArray firstArray(JSONObject issue, String... sourceFields) {
        for (String sourceField : sourceFields) {
            Object value = issue.get(sourceField);
            if (value instanceof JSONArray array) {
                return array;
            }
            if (value instanceof List<?> list) {
                JSONArray array = new JSONArray();
                array.addAll(list);
                return array;
            }
        }
        return null;
    }

    private ExternalPrincipalRef principal(Object item) {
        if (item instanceof JSONObject user) {
            return ExternalPrincipalRef.user(
                    firstNonBlank(str(user, "staffId"), str(user, "userStaffId"), str(user, "id")),
                    firstNonBlank(str(user, "name"), str(user, "userName"), str(user, "nickName"),
                            str(user, "displayName")));
        }
        if (item instanceof Number || item instanceof String) {
            return ExternalPrincipalRef.user(String.valueOf(item), null);
        }
        return null;
    }

    private String toLifecycle(JSONObject issue) {
        if (Boolean.TRUE.equals(issue.getBoolean("isDeleted")) || Boolean.TRUE.equals(issue.getBoolean("deleted"))) {
            return "DELETED";
        }
        if (Boolean.TRUE.equals(issue.getBoolean("isClosed")) || Boolean.TRUE.equals(issue.getBoolean("closed"))) {
            return "CLOSED";
        }
        return "ACTIVE";
    }

    private String aoneWebUrl(String projectId, String workType, String externalId) {
        if (webBaseUrl == null
                || projectId == null || projectId.isBlank()
                || externalId == null || externalId.isBlank()) {
            return null;
        }
        String type = switch (workType) {
            case "REQ" -> "req";
            case "BUG" -> "bug";
            default -> "task";
        };
        return webBaseUrl + "/v2/project/" + projectId + "/" + type + "/" + externalId;
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
