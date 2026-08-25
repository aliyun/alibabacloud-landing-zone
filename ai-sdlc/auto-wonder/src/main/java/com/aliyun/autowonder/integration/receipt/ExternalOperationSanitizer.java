package com.aliyun.autowonder.integration.receipt;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExternalOperationSanitizer {

    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(authorization|access[-_ ]?key|token|secret|password)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final int MAX_ERROR_LENGTH = 2_000;

    private ExternalOperationSanitizer() {
    }

    public static String sanitizeJson(String json) {
        Object parsed = JSON.parse(json == null || json.isBlank() ? "{}" : json);
        return JSON.toJSONString(sanitizeValue(null, parsed));
    }

    public static String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = INLINE_SECRET.matcher(value);
        return matcher.replaceAll("$1$2[REDACTED]");
    }

    public static String sanitizeError(String value) {
        String sanitized = sanitizeText(value == null || value.isBlank() ? "external operation failed" : value);
        return sanitized.length() <= MAX_ERROR_LENGTH ? sanitized : sanitized.substring(0, MAX_ERROR_LENGTH);
    }

    private static Object sanitizeValue(String key, Object value) {
        if (isSecretKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof JSONObject object) {
            JSONObject result = new JSONObject(true);
            for (String childKey : object.keySet()) {
                result.put(childKey, sanitizeValue(childKey, object.get(childKey)));
            }
            return result;
        }
        if (value instanceof JSONArray array) {
            JSONArray result = new JSONArray();
            for (Object item : array) {
                result.add(sanitizeValue(null, item));
            }
            return result;
        }
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        return value;
    }

    private static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("authorization") || normalized.contains("accesstoken")
                || normalized.contains("accesskey") || normalized.contains("secret")
                || normalized.contains("password") || normalized.equals("token")
                || normalized.contains("credential");
    }
}
