package com.aliyun.autowonder.conversation;

/** A definite delivery failure (credentials, invalid request) that must not be retried. */
public class ImDeliveryFailedException extends IllegalStateException {
    public ImDeliveryFailedException(String message) {
        super(message);
    }

    public ImDeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
