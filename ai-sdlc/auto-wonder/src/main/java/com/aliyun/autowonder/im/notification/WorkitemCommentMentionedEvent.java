package com.aliyun.autowonder.im.notification;

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
        String commentContentMd) {
}
