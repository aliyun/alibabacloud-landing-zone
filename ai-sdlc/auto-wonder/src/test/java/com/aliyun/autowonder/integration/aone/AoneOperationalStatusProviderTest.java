package com.aliyun.autowonder.integration.aone;

import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
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
        FakeAoneClient() { super(AoneClientTestSupport.enabledProperties()); }
        String path;
        Map<String, ?> query;

        @Override
        public JSONObject get(AoneOpenApiConfig config, String path, Map<String, ?> query) {
            this.path = path;
            this.query = query;
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
    }
}
