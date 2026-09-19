package com.aliyun.autowonder.im.notification;

public record ImNotificationEnvelope(
        String messageId,
        ImNotificationTask task,
        long deliveryCount,
        boolean valid,
        String errorReason,
        String payloadSummary,
        String payload) {

    public ImNotificationEnvelope(String messageId, ImNotificationTask task, long deliveryCount) {
        this(messageId, task, deliveryCount, true, null, null, null);
    }

    public ImNotificationEnvelope(String messageId, ImNotificationTask task, long deliveryCount, String payload) {
        this(messageId, task, deliveryCount, true, null, null, payload);
    }

    public static ImNotificationEnvelope invalid(String messageId, long deliveryCount, String errorReason,
                                                 String payloadSummary, String payload) {
        return new ImNotificationEnvelope(messageId, null, deliveryCount, false, errorReason,
                payloadSummary, payload);
    }

    public boolean isValid() {
        return valid;
    }
}
