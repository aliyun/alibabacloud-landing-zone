package com.aliyun.autowonder.im.notification;

public record ImNotificationEnvelope(
        String messageId,
        ImNotificationTask task,
        long deliveryCount,
        boolean valid,
        String errorReason) {

    public ImNotificationEnvelope(String messageId, ImNotificationTask task, long deliveryCount) {
        this(messageId, task, deliveryCount, true, null);
    }

    public static ImNotificationEnvelope invalid(String messageId, long deliveryCount, String errorReason) {
        return new ImNotificationEnvelope(messageId, null, deliveryCount, false, errorReason);
    }

    public boolean isValid() {
        return valid;
    }
}
