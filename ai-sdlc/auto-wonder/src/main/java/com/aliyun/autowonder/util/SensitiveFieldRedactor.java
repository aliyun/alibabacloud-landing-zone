package com.aliyun.autowonder.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SensitiveFieldRedactor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final byte[] REDACTED_BODY = "[REDACTED]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final TextNode REDACTED = TextNode.valueOf("[REDACTED]");
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "appsecret",
            "externaluserid",
            "credentialref",
            "password",
            "passwordhash",
            "token",
            "accesstoken",
            "refreshtoken");

    private SensitiveFieldRedactor() {
    }

    public static byte[] redactJson(byte[] content) {
        if (content == null || content.length == 0) {
            return content;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(content);
            if (root == null) {
                return REDACTED_BODY.clone();
            }
            redact(root);
            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (Exception ignored) {
            return REDACTED_BODY.clone();
        }
    }

    private static void redact(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_FIELDS.contains(normalizeFieldName(field.getKey()))) {
                    object.set(field.getKey(), REDACTED);
                } else {
                    redact(field.getValue());
                }
            }
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(SensitiveFieldRedactor::redact);
        }
    }

    private static String normalizeFieldName(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
    }
}
