package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;

import java.util.List;
import java.util.Objects;

/**
 * 会话问答的 schema 兜底：用户回传的 accept 载荷必须落在卡片声明的题目上，
 * 否则数字人会把「只答了一部分」当成完整答案继续执行。
 *
 * <p>口径对齐派发请求的 {@code DispatchRequestService#validateAnswer}（required、
 * 未知字段、类型），但会话卡片兼容更宽：没有 schema、schema 解析不出 properties，
 * 或含前端只能降级成 JSON 文本框的字段时，退回「仅校验是 JSON 对象」的历史行为。
 */
final class ElicitationAnswerValidator {

    private ElicitationAnswerValidator() {
    }

    static void validate(String schemaJson, JSONObject content) {
        JSONObject schema = parseSchema(schemaJson);
        JSONObject properties = strictProperties(schema);
        if (properties == null) return;
        JSONArray required = schema.getJSONArray("required");
        if (required != null) {
            for (Object key : required) {
                String name = String.valueOf(key);
                Object value = content.get(name);
                if (!content.containsKey(name) || value == null
                        || value instanceof String text && text.isBlank()
                        || value instanceof List<?> list && list.isEmpty()) {
                    throw invalid("conversation.elicitation_required_answers_missing", null,
                            "elicitation answer misses required field: " + name);
                }
            }
        }
        for (String name : content.keySet()) {
            JSONObject property = properties.getJSONObject(name);
            if (property == null) {
                throw invalid("conversation.elicitation_unknown_answer_field", new Object[]{name},
                        "elicitation answer contains unknown field: " + name);
            }
            if (!typeMatches(Objects.toString(property.get("type"), "string"), content.get(name))) {
                throw invalid("conversation.elicitation_answer_type_invalid", new Object[]{name},
                        "elicitation answer field has wrong type: " + name);
            }
        }
    }

    private static JSONObject parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return null;
        try {
            return JSON.parseObject(schemaJson);
        } catch (RuntimeException e) {
            // 落库的 schema 读不出来时没有可依据的约束，退回仅对象校验。
            return null;
        }
    }

    /**
     * 只有整张卡片都能被前端的分步控件表达时才做严格校验。无法表达（object 等）
     * 或数组没有可枚举选项的字段会降级成 JSON 文本框，回传内容按原 schema 展开，
     * 与声明字段不再一一对应，此时不能按未知字段拒绝。
     */
    private static JSONObject strictProperties(JSONObject schema) {
        JSONObject properties = schema == null ? null : schema.getJSONObject("properties");
        if (properties == null || properties.isEmpty()) return null;
        for (String name : properties.keySet()) {
            Object declared = properties.get(name);
            if (!(declared instanceof JSONObject property) || !expressible(property)) return null;
        }
        return properties;
    }

    private static boolean expressible(JSONObject property) {
        String type = Objects.toString(property.get("type"), "");
        return switch (type) {
            case "string", "boolean", "number", "integer" -> true;
            case "array" -> {
                Object items = property.get("items");
                yield items instanceof JSONObject object
                        && (object.get("enum") != null || object.get("anyOf") != null
                        || object.get("oneOf") != null);
            }
            default -> false;
        };
    }

    private static boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && Double.compare(number.doubleValue(), Math.rint(number.doubleValue())) == 0;
            case "array" -> value instanceof List<?> values && values.stream().allMatch(String.class::isInstance);
            default -> true;
        };
    }

    private static BizException invalid(String key, Object[] args, String diagnostic) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, key, args, diagnostic);
    }
}
