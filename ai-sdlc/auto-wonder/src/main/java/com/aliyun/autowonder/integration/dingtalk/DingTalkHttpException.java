package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public final class DingTalkHttpException extends IllegalStateException {
    private final int status;
    private final String responseBody;
    private final String providerCode;
    private final String providerRequestId;

    public DingTalkHttpException(int status, String responseBody) {
        this(status, responseBody, parse(responseBody));
    }

    private DingTalkHttpException(int status, String responseBody, Metadata metadata) {
        super(message(status, metadata));
        this.status = status;
        this.responseBody = responseBody;
        this.providerCode = metadata.code;
        this.providerRequestId = metadata.requestId;
    }

    public int getStatus() {
        return status;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    String getResponseBody() {
        return responseBody;
    }

    private static String message(int status, Metadata metadata) {
        return "DingTalk request failed: HTTP " + status
                + (metadata.code == null ? "" : " code=" + metadata.code)
                + (metadata.requestId == null ? "" : " requestId=" + metadata.requestId);
    }

    private static Metadata parse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Metadata.EMPTY;
        }
        try {
            JSONObject json = JSON.parseObject(responseBody);
            return new Metadata(
                    safe(json.getString("code")),
                    safe(firstNonBlank(json.getString("requestid"), json.getString("requestId"))));
        } catch (RuntimeException ignored) {
            return Metadata.EMPTY;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String safe(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_.:-]{1,128}$")) {
            return null;
        }
        return value;
    }

    private record Metadata(String code, String requestId) {
        private static final Metadata EMPTY = new Metadata(null, null);
    }
}
