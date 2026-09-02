package com.aliyun.autowonder.repo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateRepoRequest {
    private String name;
    private String url;
    private String defaultBranch;
    private String description;
    private boolean namePresent;
    private boolean urlPresent;
    private boolean defaultBranchPresent;
    private boolean descriptionPresent;

    /**
     * Field presence matters: an omitted field stays unchanged while an explicit
     * JSON null clears a nullable column, so the body is parsed manually instead
     * of relying on data binding.
     */
    public static UpdateRepoRequest fromJson(JsonNode body) {
        UpdateRepoRequest req = new UpdateRepoRequest();
        if (body == null) {
            return req;
        }
        if (body.has("name")) {
            req.setNamePresent(true);
            req.setName(textOrNull(body.get("name")));
        }
        if (body.has("url")) {
            req.setUrlPresent(true);
            req.setUrl(textOrNull(body.get("url")));
        }
        if (body.has("defaultBranch")) {
            req.setDefaultBranchPresent(true);
            req.setDefaultBranch(textOrNull(body.get("defaultBranch")));
        }
        if (body.has("description")) {
            req.setDescriptionPresent(true);
            req.setDescription(textOrNull(body.get("description")));
        }
        return req;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
