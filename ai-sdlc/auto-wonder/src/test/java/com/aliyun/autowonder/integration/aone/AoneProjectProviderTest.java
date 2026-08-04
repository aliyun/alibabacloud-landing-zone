package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.provider.ExternalProjectMember;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AoneProjectProviderTest {

    @Test
    void listMembersFlattensRoleGroups() {
        AoneProjectProvider provider = new AoneProjectProvider(new FakeAoneClient());

        List<ExternalProjectMember> members = provider.listMembers(config(), "2161074");

        assertEquals(2, members.size());
        assertEquals("372094", members.get(0).getStaffId());
        assertEquals("礼川", members.get(0).getDisplayName());
        assertEquals("管理员", members.get(0).getRoleName());
        assertEquals("220791", members.get(1).getStaffId());
        assertEquals("管理员", members.get(1).getRoleName());
    }

    private AoneOpenApiConfig config() {
        return new AoneOpenApiConfig("http://aone-api.alibaba-inc.com", "auto-wonder", "secret", "1");
    }

    private static class FakeAoneClient extends AoneOpenApiClient {
        private FakeAoneClient() {
            super(AoneClientTestSupport.enabledProperties());
        }

        @Override
        public JSONObject get(AoneOpenApiConfig config, String path, Map<String, ?> query) {
            JSONObject role = new JSONObject();
            role.put("id", 100031);
            role.put("name", "管理员");
            JSONArray users = new JSONArray();
            JSONObject user1 = new JSONObject();
            user1.put("id", 50596323);
            user1.put("nickName", "礼川");
            user1.put("realName", "李港晨");
            user1.put("staffId", "372094");
            users.add(user1);
            JSONObject user2 = new JSONObject();
            user2.put("id", 31848611);
            user2.put("nickName", "觉谦");
            user2.put("staffId", "220791");
            users.add(user2);
            role.put("users", users);

            JSONObject result = new JSONObject();
            JSONArray roles = new JSONArray();
            roles.add(role);
            result.put("result", roles);
            return result;
        }
    }
}
