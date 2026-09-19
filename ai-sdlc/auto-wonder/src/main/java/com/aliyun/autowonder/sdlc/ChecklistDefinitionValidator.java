package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;

/** Definition-only applicability opt-in. Execution results never grant permission to skip a check. */
final class ChecklistDefinitionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static void validate(String json) {
        if (json == null || json.isBlank()) return;
        JsonNode items;
        try { items = MAPPER.readTree(json); }
        catch (Exception e) { throw invalid("不是合法的 JSON"); }
        if (!items.isArray()) throw invalid("必须为数组");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String id;
            if (item.isTextual() && !item.asText().isBlank()) {
                id = "cl_" + i;
            } else if (item.isObject() && text(item, "id") && text(item, "text")) {
                id = item.get("id").asText();
                if (item.has("allowNotApplicable") && !item.get("allowNotApplicable").isBoolean()) {
                    throw invalid("检查项 " + id + " 的 allowNotApplicable 必须为布尔值");
                }
                if (item.path("allowNotApplicable").asBoolean(false) && !text(item, "notApplicableWhen")) {
                    throw invalid("检查项 " + id + " 允许不适用时必须填写 notApplicableWhen 条件");
                }
            } else {
                throw invalid("第 " + (i + 1) + " 项必须为非空文本或包含非空 id、text 的对象");
            }
            if (!ids.add(id)) throw invalid("检查项 id 重复: " + id);
        }
    }

    private static boolean text(JsonNode item, String field) {
        return item.path(field).isTextual() && !item.path(field).asText().isBlank();
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, "checklistJson " + message);
    }
}
