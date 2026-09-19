package com.aliyun.autowonder.dispatch;

/** Non-retriable failure when an otherwise eligible executor lacks a required wire feature. */
public class ExecutorProtocolCompatibilityException extends IllegalStateException {
    private final String requiredFeature;

    public ExecutorProtocolCompatibilityException(String requiredFeature) {
        super("Executor runtime does not support required protocol feature " + requiredFeature);
        this.requiredFeature = requiredFeature;
    }

    public String getRequiredFeature() {
        return requiredFeature;
    }
}
