package com.aliyun.autowonder.im.notification;

public record WorkitemHumanAssignedEvent(
        long tenantId,
        long workitemId,
        String workitemTitle,
        long workitemEventId,
        long recipientUserId,
        String actorType,
        long actorRef,
        String actorDisplayName,
        String requestId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String actorNameKind) {
    public WorkitemHumanAssignedEvent(long tenantId, long workitemId, String workitemTitle, long workitemEventId,
            long recipientUserId, String actorType, long actorRef, String actorDisplayName, String requestId) {
        this(tenantId, workitemId, workitemTitle, workitemEventId, recipientUserId, actorType, actorRef,
                actorDisplayName, requestId, null);
    }
}
