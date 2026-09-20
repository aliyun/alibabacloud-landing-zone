package com.aliyun.autowonder.category.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCategoryRequest {
    private String name;
    private Long parentId;
    private String description;
    private boolean namePresent;
    private boolean parentIdPresent;
    private boolean descriptionPresent;

    /**
     * 字段是否出现语义不同：缺省保持原值，显式 JSON null 表示清空（parentId 的 null 即移动为顶级分类），
     * 所以手动解析请求体，而不是依赖数据绑定。
     */
    public static UpdateCategoryRequest fromJson(JsonNode body) {
        UpdateCategoryRequest req = new UpdateCategoryRequest();
        if (body == null) {
            return req;
        }
        if (body.has("name")) {
            req.setNamePresent(true);
            req.setName(body.get("name").isNull() ? null : body.get("name").asText());
        }
        if (body.has("parentId")) {
            req.setParentIdPresent(true);
            req.setParentId(longOrNull(body.get("parentId")));
        }
        if (body.has("description")) {
            req.setDescriptionPresent(true);
            req.setDescription(body.get("description").isNull() ? null : body.get("description").asText());
        }
        return req;
    }

    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
            throw new com.aliyun.autowonder.common.error.BizException(
                    com.aliyun.autowonder.common.error.ErrorCode.PARAM_INVALID, "parentId 必须是正整数或 null");
        }
        return node.asLong();
    }

}
