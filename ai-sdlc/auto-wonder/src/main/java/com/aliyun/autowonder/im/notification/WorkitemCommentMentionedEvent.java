package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.dispatch.ExecutionSourceType;

public record WorkitemCommentMentionedEvent(
        long tenantId,
        long workitemId,
        String workitemTitle,
        long commentId,
        long recipientUserId,
        String actorType,
        long actorRef,
        String actorDisplayName,
        String requestId,
        String commentContentMd,
        String sourceType) {

    public static final String SOURCE_WORKITEM = "WORKITEM";
    public static final String SOURCE_SCHEDULED_TASK_RUN = ExecutionSourceType.SCHEDULED_TASK_RUN.name();

    public WorkitemCommentMentionedEvent(long tenantId,
                                         long workitemId,
                                         String workitemTitle,
                                         long commentId,
                                         long recipientUserId,
                                         String actorType,
                                         long actorRef,
                                         String actorDisplayName,
                                         String requestId,
                                         String commentContentMd) {
        this(tenantId, workitemId, workitemTitle, commentId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, commentContentMd, null);
    }

    public boolean isScheduledTaskRun() {
        return SOURCE_SCHEDULED_TASK_RUN.equals(sourceType);
    }
}
