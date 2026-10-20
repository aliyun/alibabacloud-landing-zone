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
        catch (Exception e) { throw invalid("sdlc.checklist.json", null, "不是合法的 JSON"); }
        if (!items.isArray()) throw invalid("sdlc.checklist.array", null, "必须为数组");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String id;
            if (item.isTextual() && !item.asText().isBlank()) {
                id = "cl_" + i;
            } else if (item.isObject() && text(item, "id") && text(item, "text")) {
                id = item.get("id").asText();
                if (item.has("allowNotApplicable") && !item.get("allowNotApplicable").isBoolean()) {
                    throw invalid("sdlc.checklist.boolean", new Object[]{id}, "检查项 " + id + " 的 allowNotApplicable 必须为布尔值");
                }
                if (item.path("allowNotApplicable").asBoolean(false) && !text(item, "notApplicableWhen")) {
                    throw invalid("sdlc.checklist.condition", new Object[]{id},
                            "检查项 " + id + " 允许不适用时必须填写 notApplicableWhen 条件");
                }
            } else {
                throw invalid("sdlc.checklist.item", new Object[]{i + 1}, "第 " + (i + 1) + " 项必须为非空文本或包含非空 id、text 的对象");
            }
            if (!ids.add(id)) throw invalid("sdlc.checklist.duplicate", new Object[]{id}, "检查项 id 重复: " + id);
        }
    }

    private static boolean text(JsonNode item, String field) {
        return item.path(field).isTextual() && !item.path(field).asText().isBlank();
    }

    private static BizException invalid(String key, Object[] args, String message) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, key, args, "checklistJson " + message);
    }
}
