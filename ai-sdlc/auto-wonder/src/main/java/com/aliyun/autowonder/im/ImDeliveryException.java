package com.aliyun.autowonder.im;

public final class ImDeliveryException extends RuntimeException {
    private final boolean retryable;
    private final String providerCode;
    private final String providerRequestId;

    public ImDeliveryException(String provider, boolean retryable, String providerCode,
                               String providerRequestId, Throwable cause) {
        super(message(provider, retryable, providerCode, providerRequestId), cause);
        this.retryable = retryable;
        this.providerCode = safe(providerCode);
        this.providerRequestId = safe(providerRequestId);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    private static String message(String provider, boolean retryable, String code, String requestId) {
        return "IM delivery failed provider=" + safeOrUnknown(provider)
                + " retryable=" + retryable
                + " providerCode=" + safeOrUnknown(code)
                + " providerRequestId=" + safeOrUnknown(requestId);
    }

    private static String safeOrUnknown(String value) {
        String safe = safe(value);
        return safe == null ? "unknown" : safe;
    }

    private static String safe(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_.:-]{1,128}$")) {
            return null;
        }
        return value;
    }
}
