package com.aliyun.autowonder.conversation;

/**
 * The provider may or may not have accepted the message (timeout, lost response, expired lease).
 * Never retried blindly: it is surfaced as UNKNOWN instead of claiming exactly-once delivery.
 */
public class ImDeliveryUnknownException extends IllegalStateException {
    public ImDeliveryUnknownException(String message) {
        super(message);
    }

    public ImDeliveryUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
