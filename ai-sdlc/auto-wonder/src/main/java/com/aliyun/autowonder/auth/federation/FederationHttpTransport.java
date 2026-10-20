package com.aliyun.autowonder.auth.federation;

import java.io.IOException;
import java.util.Map;

/** 出站 HTTP 访问（OIDC token / JWKS 端点）。独立成接口便于在测试中替换为桩实现。 */
public interface FederationHttpTransport {
    String get(String url, Map<String, String> headers) throws IOException;

    String postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException;
}
