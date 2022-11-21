package org.hz.minigroup.common.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.SimpleDateFormatSerializer;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Type;
import java.util.Date;
import java.util.List;

public class JsonUtils {

    // fastjson 的序列化配置
    public static final SerializeConfig fastjson_serializeConfig_noYear = new SerializeConfig();
    public static final SerializeConfig fastjson_serializeConfig_time = new SerializeConfig();
    public static final SerializeConfig fastjson_free_datetime = new SerializeConfig();

    // 默认打出所有属性(即使属性值为null)|属性排序输出,为了配合历史记录
    private static final SerializerFeature[] fastJsonFeatures = {SerializerFeature.WriteMapNullValue,
            SerializerFeature.WriteEnumUsingToString, SerializerFeature.SortField,
            SerializerFeature.DisableCircularReferenceDetect};

    private static final SerializerFeature[] fastJsonFeaturesForWeb = {SerializerFeature.WriteMapNullValue,
            SerializerFeature.WriteEnumUsingToString, SerializerFeature.SortField,
            SerializerFeature.DisableCircularReferenceDetect};

    static {
        fastjson_serializeConfig_time.put(Date.class, new SimpleDateFormatSerializer("yyyy-MM-dd HH:mm:ss"));
    }

    @SuppressWarnings("unchecked")
    public static final <T> T parseObject(String input, Type clazz) {
        return (T) JSON.parseObject(input, clazz);
    }

    public static <T> T parseObject(String item, Class<T> clazz) {
        if (StringUtils.isBlank(item)) {
            return null;
        }
        return JSON.parseObject(item, clazz);
    }

    public static <T> T parseObject(String item, TypeReference<T> type) {
        if (StringUtils.isBlank(item)) {
            return null;
        }
        return JSON.parseObject(item, type);
    }

    public static final <T> List<T> parseArray(String text, Class<T> clazz) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        return JSON.parseArray(text, clazz);
    }

    @SuppressWarnings("unchecked")
    public static final <T> T getValueFormJsonString(String text, String key, Class<T> clazz) {
        JSONObject object = JSON.parseObject(text);
        if (String.class.equals(clazz)) {
            return (T) object.getString(key);
        } else if (Long.class.equals(clazz)) {
            return (T) object.getLong(key);
        } else {
            throw new RuntimeException("");
        }
    }

    public static String toJsonString(Object object) {
        return toJsonString(object, fastjson_serializeConfig_noYear, fastJsonFeatures);
    }

    public static String toJsonStringWithDatetime(Object object) {
        return toJsonString(object, fastjson_serializeConfig_time, fastJsonFeatures);
    }

    public static String toJsonStringForWeb(Object object) {
        return toJsonString(object, fastjson_serializeConfig_time, fastJsonFeaturesForWeb);
    }

    private static String toJsonString(Object object, SerializeConfig serializeConfig, SerializerFeature[] features) {
        if (null == object) {
            return "";
        }
        return JSON.toJSONString(object, serializeConfig, features);
    }
}
