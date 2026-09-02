package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
        String commentContentMd,
        String sourceType) {

    public static final String TYPE_WORKITEM_ASSIGNED = "WORKITEM_ASSIGNED";
    public static final String TYPE_COMMENT_MENTION = "COMMENT_MENTION";
    public static final String TYPE_WORKSPACE_ACCESS_REQUEST = "WORKSPACE_ACCESS_REQUEST";
    public static final String TYPE_WORKSPACE_ACCESS_REVIEWED = "WORKSPACE_ACCESS_REVIEWED";
    public static final String SOURCE_SCHEDULED_TASK_RUN = ExecutionSourceType.SCHEDULED_TASK_RUN.name();

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
                actorDisplayName, requestId, workitemTitle, TYPE_WORKITEM_ASSIGNED, null, null);
    }

    public ImNotificationTask(String notificationKey,
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
        this(notificationKey, workitemEventId, tenantId, workitemId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, workitemTitle, notificationType, commentContentMd, null);
    }

    public ImNotificationTask {
        if (notificationType == null || notificationType.isBlank()) {
            notificationType = TYPE_WORKITEM_ASSIGNED;
        }
    }

    @JsonIgnore
    public boolean isScheduledTaskRun() {
        return SOURCE_SCHEDULED_TASK_RUN.equals(sourceType);
    }
}
