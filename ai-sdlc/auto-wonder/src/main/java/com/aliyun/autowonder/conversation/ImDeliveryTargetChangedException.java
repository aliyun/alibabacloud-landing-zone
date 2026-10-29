package com.aliyun.autowonder.conversation;

/** The persisted route no longer matches, so the reply must not be sent (CANCELED). */
public class ImDeliveryTargetChangedException extends IllegalStateException {
    public ImDeliveryTargetChangedException(String message) {
        super(message);
    }

    public ImDeliveryTargetChangedException(String message, Throwable cause) {
        super(message, cause);
    }
}
