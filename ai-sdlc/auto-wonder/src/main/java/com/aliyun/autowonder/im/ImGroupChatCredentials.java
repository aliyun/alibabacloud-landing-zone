package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Encrypted credential bundle for the platform shared group-chat application. */
public record ImGroupChatCredentials(String appSecret, String verificationToken, String encryptKey) {
    public static final int FORMAT_VERSION = 1;

    public static ImGroupChatCredentials parse(ObjectMapper mapper, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw invalid();
            }
            JsonNode version = node.get("v");
            if (version == null || !version.isIntegralNumber()
                    || version.asInt() != FORMAT_VERSION) {
                throw unsupportedVersion();
            }
            return new ImGroupChatCredentials(
                    text(node, "appSecret"), text(node, "verificationToken"),
                    text(node, "encryptKey"));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    public String toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", FORMAT_VERSION);
        if (hasText(appSecret)) {
            node.put("appSecret", appSecret);
        }
        if (hasText(verificationToken)) {
            node.put("verificationToken", verificationToken);
        }
        if (hasText(encryptKey)) {
            node.put("encryptKey", encryptKey);
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize group-chat credentials", e);
        }
    }

    public boolean isEmpty() {
        return !hasText(appSecret) && !hasText(verificationToken) && !hasText(encryptKey);
    }

    public ImGroupChatCredentials merge(String newSecret, String newVerificationToken,
                                        String newEncryptKey, boolean clearEncryptKey) {
        return new ImGroupChatCredentials(
                hasText(newSecret) ? newSecret : appSecret,
                hasText(newVerificationToken) ? newVerificationToken : verificationToken,
                clearEncryptKey ? null : (hasText(newEncryptKey) ? newEncryptKey : encryptKey));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.asText();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static BizException invalid() {
        return BizException.keyed(ErrorCode.IM_CHANNEL_NOT_READY,
                "im.group_credentials_invalid", new Object[0], "共享群聊凭据格式不正确");
    }

    private static BizException unsupportedVersion() {
        return BizException.keyed(ErrorCode.IM_CHANNEL_NOT_READY,
                "im.group_credentials_version_unsupported", new Object[0],
                "共享群聊凭据格式版本不受支持");
    }
}
