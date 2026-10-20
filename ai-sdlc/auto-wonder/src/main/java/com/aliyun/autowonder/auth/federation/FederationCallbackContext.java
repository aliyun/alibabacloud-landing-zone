package com.aliyun.autowonder.auth.federation;

import java.util.Map;

/** 回调请求的协议无关视图：原始参数（取首个值）与本次回调的外部可达地址。 */
public record FederationCallbackContext(String callbackUrl, Map<String, String> parameters) {
    public FederationCallbackContext {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String parameter(String name) {
        return parameters.get(name);
    }
}
