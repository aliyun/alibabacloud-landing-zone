package com.aliyun.autowonder.im.notification;

final class SafeImNotificationDeliveryException extends RuntimeException {

    private SafeImNotificationDeliveryException(String safeMessage, StackTraceElement[] stackTrace) {
        super(safeMessage, null, false, true);
        setStackTrace(stackTrace == null ? new StackTraceElement[0] : stackTrace.clone());
    }

    static SafeImNotificationDeliveryException from(Throwable failure) {
        if (failure == null) {
            return new SafeImNotificationDeliveryException(
                    "IM notification delivery failure details redacted",
                    new StackTraceElement[0]);
        }
        return new SafeImNotificationDeliveryException(
                failure.getClass().getSimpleName() + " details redacted",
                failure.getStackTrace());
    }
}
