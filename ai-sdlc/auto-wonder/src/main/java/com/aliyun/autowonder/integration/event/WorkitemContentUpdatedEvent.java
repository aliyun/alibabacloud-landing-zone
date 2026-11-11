package com.aliyun.autowonder.integration.event;

/** 标题或正文任一变化后发布；titleChanged 区分“只改正文”，供后置可选逻辑跳过无效触发。 */
public record WorkitemContentUpdatedEvent(long tenantId, long workitemId, String title,
                                          String contentMd, long userId, boolean titleChanged) {
}
