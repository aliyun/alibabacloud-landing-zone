package com.aliyun.autowonder.integration.feishu;

/**
 * Typed Feishu API failure. Callers distinguish "definitely rejected" from "unknown outcome"
 * so reply delivery never blindly resends an ambiguous request.
 */
public final class FeishuApiException extends IllegalStateException {
    private final Integer httpStatus;
    private final Integer businessCode;
    private final boolean networkFailure;

    public FeishuApiException(String message, Integer httpStatus, Integer businessCode,
            boolean networkFailure) {
        super(message);
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
        this.networkFailure = networkFailure;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getBusinessCode() {
        return businessCode;
    }

    public boolean isNetworkFailure() {
        return networkFailure;
    }
}
