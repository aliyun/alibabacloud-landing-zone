package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 手工验证 Aone 评论 userId 能否通过 UserApiFacade 查询到真实身份。
 *
 * <p>不会被普通测试或 CI 自动执行。运行方式：</p>
 * <pre>
 * AONE_ACCESS_SECRET='...' mvn -Dtest=AoneUserApiManualTest \
 *   -Daone.manual.user-api.enabled=true test
 * </pre>
 *
 * <p>可选环境变量：AONE_USER_ID、AONE_BASE_URL、AONE_CLIENT_KEY、AONE_REGION_ID。</p>
 */
class AoneUserApiManualTest {

    private static final String DEFAULT_BASE_URL = "http://aone-api.alibaba-inc.com";
    private static final String DEFAULT_CLIENT_KEY = "terraform-competition-dashboard";
    private static final String DEFAULT_REGION_ID = "1";
    // 评论 126034247 的 userId；该评论可通过 A1 验证作者为辰羿。
    private static final String DEFAULT_USER_ID = "48730503";

    @Test
    void getByIdReturnsIdentityForCommentAuthor() {
        assumeTrue(Boolean.getBoolean("aone.manual.user-api.enabled"),
                "仅手工执行：添加 -Daone.manual.user-api.enabled=true");

        String accessSecret = requiredEnv("AONE_ACCESS_SECRET");
        String userId = envOrDefault("AONE_USER_ID", DEFAULT_USER_ID);
        AoneOpenApiConfig config = new AoneOpenApiConfig(
                envOrDefault("AONE_BASE_URL", DEFAULT_BASE_URL),
                envOrDefault("AONE_CLIENT_KEY", DEFAULT_CLIENT_KEY),
                accessSecret,
                envOrDefault("AONE_REGION_ID", DEFAULT_REGION_ID));

        JSONObject result = new AoneOpenApiClient(AoneClientTestSupport.enabledProperties()).get(config,
                "/ak/project/openapi/UserApiFacade/getById", Map.of("id", userId));

        assertNotNull(result);
        System.out.println("Aone user lookup identity=" + identitySummary(result));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), "缺少环境变量 " + name);
        return value;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 仅打印身份字段，避免把用户资料中的无关字段写入控制台日志。 */
    private static JSONObject identitySummary(JSONObject result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String field : new String[]{"id", "userId", "staffId", "nickName", "realName", "name", "displayName"}) {
            if (result.containsKey(field)) {
                summary.put(field, result.get(field));
            }
        }
        return new JSONObject(summary);
    }
}
