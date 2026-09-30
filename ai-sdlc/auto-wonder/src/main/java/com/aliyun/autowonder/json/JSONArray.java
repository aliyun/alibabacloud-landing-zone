package com.aliyun.autowonder.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class JSONArray extends ArrayList<Object> {
    public JSONArray() {}

    public JSONArray(Collection<?> values) {
        if (values != null) {
            values.forEach(this::add);
        }
    }

    @Override
    public boolean add(Object value) {
        return super.add(JSON.wrap(value));
    }

    @Override
    public void add(int index, Object value) {
        super.add(index, JSON.wrap(value));
    }

    @Override
    public boolean addAll(Collection<?> values) {
        boolean changed = false;
        for (Object value : values) {
            changed |= add(value);
        }
        return changed;
    }

    public JSONObject getJSONObject(int index) {
        Object value = get(index);
        return value == null ? null : value instanceof JSONObject object ? object : (JSONObject) JSON.wrap(value);
    }

    public JSONArray getJSONArray(int index) {
        Object value = get(index);
        return value == null ? null : value instanceof JSONArray array ? array : (JSONArray) JSON.wrap(value);
    }

    public String getString(int index) {
        Object value = get(index);
        return value == null ? null : String.valueOf(value);
    }

    public Long getLong(int index) {
        Object value = get(index);
        return value == null ? null : value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }

    public long getLongValue(int index) {
        Long value = getLong(index);
        return value == null ? 0L : value;
    }

    public <T> List<T> toJavaList(Class<T> type) {
        List<T> result = new ArrayList<>(size());
        for (Object value : this) {
            result.add(JSON.convert(value, type));
        }
        return result;
    }

    public String toJSONString() {
        return JSON.toJSONString(this);
    }
}
