package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalProject;
import com.aliyun.autowonder.integration.provider.ExternalProjectMember;
import com.aliyun.autowonder.integration.provider.ExternalProjectProvider;
import com.aliyun.autowonder.integration.provider.PageResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AoneProjectProvider implements ExternalProjectProvider {

    private final AoneOpenApiClient client;

    public AoneProjectProvider(AoneOpenApiClient client) {
        this.client = client;
    }

    @Override
    public PageResult<ExternalProject> searchProjects(AoneOpenApiConfig config, String query, int page, int pageSize) {
        JSONObject q = new JSONObject();
        q.put("region", "alibaba");
        q.put("name", query);
        q.put("page", page);
        q.put("perPage", pageSize);
        JSONObject result = client.get(config, "/ak/project/openapi/ProjectApiFacade/searchByQuery",
                Map.of("region", "alibaba", "query", q.toJSONString()));
        List<ExternalProject> items = new ArrayList<>();
        for (Object item : arrayFrom(result)) {
            if (item instanceof JSONObject obj) {
                items.add(toProject(obj));
            }
        }
        return PageResult.of(items, page, pageSize, result.getIntValue("totalCount"));
    }

    @Override
    public ExternalProject getProject(AoneOpenApiConfig config, String externalProjectId) {
        JSONObject result = client.get(config, "/ak/project/openapi/ProjectApiFacade/getProjectInfo",
                Map.of("projectId", externalProjectId, "region", "alibaba"));
        return toProject(result);
    }

    @Override
    public List<ExternalProjectMember> listMembers(AoneOpenApiConfig config, String externalProjectId) {
        JSONObject result = client.get(config, "/ak/project/openapi/ProjectApiFacade/getProjectMembers",
                Map.of("targetType", "AKProject", "targetIds", externalProjectId, "region", "alibaba"));
        List<ExternalProjectMember> members = new ArrayList<>();
        for (Object item : arrayFrom(result)) {
            if (item instanceof JSONObject obj) {
                String roleName = firstNonBlank(str(obj, "roleName"), str(obj, "role"), str(obj, "name"));
                Object users = obj.get("users");
                if (users instanceof JSONArray array) {
                    for (Object user : array) {
                        if (user instanceof JSONObject userObj) {
                            members.add(toMember(userObj, roleName));
                        }
                    }
                } else {
                    members.add(toMember(obj, roleName));
                }
            }
        }
        return members;
    }

    private ExternalProjectMember toMember(JSONObject obj, String roleName) {
        ExternalProjectMember member = new ExternalProjectMember();
        member.setExternalUserId(str(obj, "id"));
        member.setStaffId(firstNonBlank(str(obj, "staffId"), str(obj, "userId")));
        member.setDisplayName(firstNonBlank(str(obj, "nickName"), str(obj, "realName"), str(obj, "name"), str(obj, "displayName")));
        member.setRoleName(roleName);
        member.setRawJson(obj.toJSONString());
        return member;
    }

    private ExternalProject toProject(JSONObject obj) {
        ExternalProject project = new ExternalProject();
        project.setExternalId(firstNonBlank(str(obj, "id"), str(obj, "akProjectId")));
        project.setName(firstNonBlank(str(obj, "name"), str(obj, "displayName")));
        project.setRawJson(obj.toJSONString());
        return project;
    }

    private JSONArray arrayFrom(JSONObject result) {
        Object data = result.get("result");
        if (data instanceof JSONArray array) return array;
        data = result.get("data");
        if (data instanceof JSONArray array) return array;
        data = result.get("list");
        if (data instanceof JSONArray array) return array;
        JSONArray array = new JSONArray();
        if (!result.isEmpty()) array.add(result);
        return array;
    }

    private String str(JSONObject object, String key) {
        Object value = object.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
