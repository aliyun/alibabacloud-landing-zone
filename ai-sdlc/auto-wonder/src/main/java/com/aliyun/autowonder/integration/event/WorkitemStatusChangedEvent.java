package com.aliyun.autowonder.integration.event;

public record WorkitemStatusChangedEvent(long tenantId, long workitemId, long toNodeId, long userId) {
}
