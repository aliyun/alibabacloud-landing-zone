package com.aliyun.autowonder.integration.event;

public record WorkitemCommentCreatedEvent(long tenantId, long workitemId, long commentId,
                                          String actorType, long actorRef, String contentMd) {
}
