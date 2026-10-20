package com.aliyun.autowonder.auth.federation;

import java.util.Map;

/** 发起登录的结果：IdP 跳转地址 + 需要在服务端随 state 保存的校验材料。 */
public record AuthorizationRedirect(String url, Map<String, String> stateAttributes) {
    public AuthorizationRedirect {
        stateAttributes = stateAttributes == null ? Map.of() : Map.copyOf(stateAttributes);
    }
}
