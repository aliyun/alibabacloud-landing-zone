package com.aliyun.autowonder.workspace;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class IdentityTags {
    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_LENGTH = 32;
    private static final ObjectMapper STRICT_JSON_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private IdentityTags() {
    }

    public static List<String> normalize(List<String> tags) {
        if (tags == null) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw new BizException(
                        ErrorCode.PARAM_INVALID,
                        "Identity tags must not exceed 32 Java characters per tag");
            }
            normalized.add(trimmed);
            if (normalized.size() > MAX_TAGS) {
                throw new BizException(
                        ErrorCode.PARAM_INVALID,
                        "Identity tags must not contain more than 8 tags");
            }
        }
        return List.copyOf(normalized);
    }

    public static String toJson(List<String> tags) {
        return JSON.toJSONString(normalize(tags));
    }

    public static List<String> fromJson(String json) {
        if (json == null) {
            return List.of();
        }
        if (json.trim().isEmpty()) {
            throw malformedPersistedJson();
        }

        try {
            STRICT_JSON_MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw malformedPersistedJson();
        }

        final Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (JSONException exception) {
            throw malformedPersistedJson();
        }
        if (parsed == null) {
            return List.of();
        }
        if (!(parsed instanceof JSONArray array)) {
            throw malformedPersistedJson();
        }

        List<String> tags = new ArrayList<>(array.size());
        for (Object value : array) {
            if (!(value instanceof String)) {
                throw malformedPersistedJson();
            }
            tags.add((String) value);
        }
        return normalize(tags);
    }

    private static BizException malformedPersistedJson() {
        return new BizException(ErrorCode.PARAM_INVALID, "Invalid persisted identity tags JSON");
    }
}
