package com.aliyun.autowonder.json;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject extends LinkedHashMap<String, Object> {
    public JSONObject() {}

    public JSONObject(boolean ordered) {}

    public JSONObject(Map<?, ?> values) {
        if (values != null) {
            values.forEach((key, value) -> put(String.valueOf(key), JSON.wrap(value)));
        }
    }

    public static JSONObject parseObject(String json) {
        return JSON.parseObject(json);
    }

    @Override
    public Object put(String key, Object value) {
        return super.put(key, JSON.wrap(value));
    }

    public JSONObject fluentPut(String key, Object value) {
        put(key, value);
        return this;
    }

    public String getString(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        return value instanceof Map<?, ?> || value instanceof Iterable<?>
                ? JSON.toJSONString(value)
                : String.valueOf(value);
    }

    public Long getLong(String key) {
        Object value = get(key);
        return value == null ? null : value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }

    public long getLongValue(String key) {
        Long value = getLong(key);
        return value == null ? 0L : value;
    }

    public Integer getInteger(String key) {
        Object value = get(key);
        return value == null ? null : value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }

    public int getIntValue(String key) {
        Integer value = getInteger(key);
        return value == null ? 0 : value;
    }

    public Double getDouble(String key) {
        Object value = get(key);
        return value == null ? null : value instanceof Number number ? number.doubleValue() : Double.valueOf(value.toString());
    }

    public Boolean getBoolean(String key) {
        Object value = get(key);
        return value == null ? null : value instanceof Boolean bool ? bool : Boolean.valueOf(value.toString());
    }

    public boolean getBooleanValue(String key) {
        Boolean value = getBoolean(key);
        return value != null && value;
    }

    public JSONObject getJSONObject(String key) {
        Object value = get(key);
        return value == null ? null : value instanceof JSONObject object ? object : (JSONObject) JSON.wrap(value);
    }

    public JSONArray getJSONArray(String key) {
        Object value = get(key);
        return value == null ? null : value instanceof JSONArray array ? array : (JSONArray) JSON.wrap(value);
    }

    public <T> T getObject(String key, Class<T> type) {
        Object value = get(key);
        return value == null ? null : JSON.convert(value, type);
    }

    public Date getDate(String key) {
        Object value = get(key);
        return value == null ? null : JSON.convert(value, Date.class);
    }

    public <T> T toJavaObject(Class<T> type) {
        return JSON.convert(this, type);
    }

    public String toJSONString() {
        return JSON.toJSONString(this);
    }
}
