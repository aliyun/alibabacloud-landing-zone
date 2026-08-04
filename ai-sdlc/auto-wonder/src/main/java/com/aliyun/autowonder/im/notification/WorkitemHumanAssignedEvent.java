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
        String requestId) {
}
