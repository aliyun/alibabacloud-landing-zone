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
        String sourceType,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String workitemTitleKind) {

    public static final String SCHEDULED_RUN_ID_FALLBACK = "SCHEDULED_RUN_ID_FALLBACK";

    public WorkitemCommentMentionedEvent(long tenantId, long workitemId, String workitemTitle, long commentId,
            long recipientUserId, String actorType, long actorRef, String actorDisplayName, String requestId,
            String commentContentMd, String sourceType) {
        this(tenantId, workitemId, workitemTitle, commentId, recipientUserId, actorType, actorRef, actorDisplayName,
                requestId, commentContentMd, sourceType, null);
    }

    public String recipientTitle(java.util.Locale locale) {
        return isScheduledTaskRun() && SCHEDULED_RUN_ID_FALLBACK.equals(workitemTitleKind)
                ? com.aliyun.autowonder.common.i18n.SystemMessages.render("notification.scheduled_run.title_fallback",
                        new Object[]{String.valueOf(workitemId)}, workitemTitle, locale) : workitemTitle;
    }

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
