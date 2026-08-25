package com.aliyun.autowonder.integration.receipt;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ExternalOperationDigests {

    private ExternalOperationDigests() {
    }

    public static String payloadDigest(String payloadJson) {
        Object parsed = JSON.parse(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
        return sha256(JSON.toJSONString(canonical(parsed)));
    }

    public static String textDigest(String value) {
        return sha256(value == null ? "" : value.replace("\r\n", "\n"));
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof JSONObject object) {
            Map<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
            for (String key : object.keySet()) {
                sorted.put(key, canonical(object.get(key)));
            }
            return sorted;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof JSONArray array) {
            List<Object> result = new ArrayList<>(array.size());
            for (Object item : array) {
                result.add(canonical(item));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ExternalOperationDigests::canonical).toList();
        }
        return value;
    }
}
