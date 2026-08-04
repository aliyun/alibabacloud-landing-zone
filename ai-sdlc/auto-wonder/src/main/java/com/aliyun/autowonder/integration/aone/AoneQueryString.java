package com.aliyun.autowonder.integration.aone;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;

public final class AoneQueryString {

    private AoneQueryString() {
    }

    public static String toQuery(Map<String, ?> params) {
        return toQuery(params, false);
    }

    public static String toUrlEncodedQuery(Map<String, ?> params) {
        return toQuery(params, true);
    }

    private static String toQuery(Map<String, ?> params, boolean urlEncode) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String serialized = serialize(value);
            if (serialized.isEmpty() || "[]".equals(serialized)) {
                continue;
            }
            String key = urlEncode ? encode(entry.getKey()) : entry.getKey();
            String queryValue = urlEncode ? encode(serialized) : serialized;
            joiner.add(key + "=" + queryValue);
        }
        return joiner.toString();
    }

    public static String serialize(Object value) {
        if (value instanceof Collection<?> collection) {
            StringJoiner joiner = new StringJoiner(",", "[", "]");
            for (Object item : collection) {
                if (item instanceof Number || item instanceof Boolean) {
                    joiner.add(String.valueOf(item));
                } else {
                    joiner.add("\"" + escapeJsonString(String.valueOf(item)) + "\"");
                }
            }
            return joiner.toString();
        }
        return String.valueOf(value);
    }

    private static String escapeJsonString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
