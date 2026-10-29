package com.aliyun.autowonder.conversation;

/** A retryable failure where the provider definitely did not accept the message. */
public class ImDeliveryRetryableException extends IllegalStateException {
    public ImDeliveryRetryableException(String message) {
        super(message);
    }

    public ImDeliveryRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
