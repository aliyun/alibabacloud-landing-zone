package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
import com.aliyun.autowonder.integration.provider.ExternalIssueType;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AoneOperationalStatusProviderTest {

    @Test
    void parsesBatchOperationalStatusesByIssueId() {
        FakeAoneClient client = new FakeAoneClient();
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        Map<String, List<ExternalStatusOption>> statuses = provider.listOperationalStatuses(config(),
                "WORKER_1782377321313", List.of("84189105", "84189109"));

        assertEquals("/issue/openapi/IssueTopService/getOperationalStatus", client.path);
        assertEquals("WORKER_1782377321313", client.query.get("staffId"));
        assertEquals(List.of(84189105L, 84189109L), client.query.get("issueIds"));
        assertEquals("待处理", statuses.get("84189105").get(0).getName());
        assertEquals("100005", statuses.get("84189105").get(0).getExternalId());
        assertEquals("Open", statuses.get("84189109").get(0).getName());
        assertEquals("32", statuses.get("84189109").get(0).getExternalId());
    }

    @Test
    void parsesStatusRulesByProjectAndIssueType() {
        FakeAoneClient client = new FakeAoneClient();
        client.statusRules = true;
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        List<ExternalStatusOption> statuses = provider.listStatusRules(config(), "2161074", 9);

        assertEquals(List.of("/issue/openapi/IssueTopService/getTemplateAndWorkflowInfo",
                "/issue/openapi/IssueTopService/getWorkflowStatusDetail"), client.paths);
        assertEquals("2161074", client.queries.get(0).get("akProjectId"));
        assertEquals(9, client.queries.get(0).get("issueTypeId"));
        assertEquals("2161074", client.queries.get(1).get("akProjectId"));
        assertEquals(42350, client.queries.get(1).get("workflowId"));
        assertEquals(2, statuses.size());
        assertEquals("待处理", statuses.get(0).getName());
        assertEquals("100005", statuses.get(0).getExternalId());
        assertEquals("已发布", statuses.get(1).getName());
        assertEquals("100003", statuses.get(1).getExternalId());
    }

    @Test
    void parsesEnabledIssueTypesByProjectStampAndStaff() {
        FakeAoneClient client = new FakeAoneClient();
        client.enabledIssueTypes = true;
        AoneWorkitemProvider provider = new AoneWorkitemProvider(client);

        List<ExternalIssueType> issueTypes = provider.listEnabledIssueTypes(config(), "2161074",
                "WORKER_1782377321313", "Bug");

        assertEquals("/issue/openapi/IssueTopService/getEnabledIssueTypes", client.path);
        assertEquals("2161074", client.query.get("akProjectId"));
        assertEquals("WORKER_1782377321313", client.query.get("staffId"));
        assertEquals("Bug", client.query.get("stamp"));
        assertEquals(2, issueTypes.size());
        assertEquals("36", issueTypes.get(0).getExternalId());
        assertEquals("Bug", issueTypes.get(0).getStamp());
        assertEquals("功能缺陷", issueTypes.get(0).getName());
        assertEquals("38", issueTypes.get(1).getExternalId());
    }

    @Test
    void mapperKeepsAoneIssueTypeIdForStatusTemplateLookup() {
        JSONObject issue = new JSONObject();
        issue.put("id", 84323280);
        issue.put("akProjectId", 2161074);
        issue.put("issueTypeId", 36);
        issue.put("stamp", "Bug");
        issue.put("subject", "bug title");

        ExternalWorkitemDetail detail = new AoneWorkitemMapper().toDetail(issue);

        assertEquals("36", detail.getExternalIssueTypeId());
        assertEquals("BUG", detail.getWorkType());
    }

    private AoneOpenApiConfig config() {
        return new AoneOpenApiConfig("http://aone-api.alibaba-inc.com", "auto-wonder", "secret", "1");
    }

    private static class FakeAoneClient extends AoneOpenApiClient {
        private FakeAoneClient() {
            super(AoneClientTestSupport.enabledProperties());
        }

        String path;
        Map<String, ?> query;
        List<String> paths = new java.util.ArrayList<>();
        List<Map<String, ?>> queries = new java.util.ArrayList<>();
        boolean statusRules;
        boolean enabledIssueTypes;

        @Override
        public JSONObject get(AoneOpenApiConfig config, String path, Map<String, ?> query) {
            this.path = path;
            this.query = query;
            this.paths.add(path);
            this.queries.add(query);
            if (statusRules) {
                if ("/issue/openapi/IssueTopService/getTemplateAndWorkflowInfo".equals(path)) {
                    JSONObject result = new JSONObject();
                    result.put("issueTemplateId", 9);
                    result.put("workflowId", 42350);
                    return result;
                }
                JSONObject result = new JSONObject();
                JSONArray statuses = new JSONArray();
                statuses.add(status("待处理", 100005));
                statuses.add(status("已发布", 100003));
                result.put("result", statuses);
                return result;
            }
            if (enabledIssueTypes) {
                JSONObject result = new JSONObject();
                JSONArray issueTypes = new JSONArray();
                issueTypes.add(issueType("功能缺陷", 36, "Bug"));
                issueTypes.add(issueType("线上问题", 38, "Bug"));
                result.put("result", issueTypes);
                return result;
            }
            JSONObject result = new JSONObject();
            JSONObject statusesByIssue = new JSONObject();
            statusesByIssue.put("84189105", array(status("待处理", 100005), status("开发中", 229667)));
            statusesByIssue.put("84189109", array(status("Open", 32), status("Fixed", 29)));
            result.put("result", statusesByIssue);
            return result;
        }

        private JSONArray array(JSONObject... objects) {
            JSONArray array = new JSONArray();
            for (JSONObject object : objects) {
                array.add(object);
            }
            return array;
        }

        private JSONObject status(String name, int id) {
            JSONObject status = new JSONObject();
            status.put("name", name);
            status.put("id", id);
            return status;
        }

        private JSONObject issueType(String name, int id, String stamp) {
            JSONObject issueType = new JSONObject();
            issueType.put("name", name);
            issueType.put("id", id);
            issueType.put("stamp", stamp);
            return issueType;
        }
    }
}
