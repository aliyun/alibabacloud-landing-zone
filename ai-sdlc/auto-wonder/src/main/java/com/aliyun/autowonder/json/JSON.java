package com.aliyun.autowonder.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project JSON facade backed exclusively by Jackson. */
public final class JSON {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private JSON() {}

    public static String toJSONString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new JSONException("Unable to serialize JSON", exception);
        }
    }

    public static Object parse(String json, Feature... ignoredFeatures) {
        try {
            return wrap(MAPPER.readValue(json, Object.class));
        } catch (JsonProcessingException exception) {
            throw new JSONException("Unable to parse JSON", exception);
        }
    }

    public static JSONObject parseObject(String json, Feature... ignoredFeatures) {
        Object parsed = parse(json, ignoredFeatures);
        if (parsed == null) {
            return null;
        }
        if (parsed instanceof JSONObject object) {
            return object;
        }
        throw new JSONException("JSON value is not an object", null);
    }

    public static <T> T parseObject(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new JSONException("Unable to parse JSON object", exception);
        }
    }

    public static JSONArray parseArray(String json) {
        Object parsed = parse(json);
        if (parsed == null) {
            return null;
        }
        if (parsed instanceof JSONArray array) {
            return array;
        }
        throw new JSONException("JSON value is not an array", null);
    }

    public static <T> List<T> parseArray(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (JsonProcessingException exception) {
            throw new JSONException("Unable to parse JSON array", exception);
        }
    }

    static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(unwrap(value), type);
    }

    static Object wrap(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray || value == null) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            JSONObject object = new JSONObject();
            map.forEach((key, item) -> object.put(String.valueOf(key), wrap(item)));
            return object;
        }
        if (value instanceof Iterable<?> iterable) {
            JSONArray array = new JSONArray();
            iterable.forEach(item -> array.add(wrap(item)));
            return array;
        }
        if (value.getClass().isArray()) {
            return wrap(MAPPER.convertValue(value, List.class));
        }
        return value;
    }

    static Object unwrap(Object value) {
        if (value instanceof JSONObject object) {
            Map<String, Object> result = new LinkedHashMap<>();
            object.forEach((key, item) -> result.put(key, unwrap(item)));
            return result;
        }
        if (value instanceof JSONArray array) {
            List<Object> result = new ArrayList<>(array.size());
            array.forEach(item -> result.add(unwrap(item)));
            return result;
        }
        return value;
    }

    static boolean isValid(String json) {
        try {
            JsonNode ignored = MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }
}
