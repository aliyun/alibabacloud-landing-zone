package com.aliyun.autowonder.im.notification;

public record ImNotificationTask(
        String notificationKey,
        long workitemEventId,
        long tenantId,
        long workitemId,
        long recipientUserId,
        String actorType,
        long actorRef,
        String actorDisplayName,
        String requestId,
        String workitemTitle,
        String notificationType,
        String commentContentMd) {

    public static final String TYPE_WORKITEM_ASSIGNED = "WORKITEM_ASSIGNED";
    public static final String TYPE_COMMENT_MENTION = "COMMENT_MENTION";

    public ImNotificationTask(String notificationKey,
                              long workitemEventId,
                              long tenantId,
                              long workitemId,
                              long recipientUserId,
                              String actorType,
                              long actorRef,
                              String actorDisplayName,
                              String requestId,
                              String workitemTitle) {
        this(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, workitemTitle, TYPE_WORKITEM_ASSIGNED, null);
    }

    public ImNotificationTask {
        if (notificationType == null || notificationType.isBlank()) {
            notificationType = TYPE_WORKITEM_ASSIGNED;
        }
    }
}
