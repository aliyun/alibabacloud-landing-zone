package com.aliyun.autowonder.im.notification;

public record ImNotificationMessageContext(
        String workspaceName,
        String statusName,
        String baseUrl,
        long tenantId,
        boolean assignedStatusFallback) {
    public ImNotificationMessageContext(String workspaceName, String statusName, String baseUrl, long tenantId) {
        this(workspaceName, statusName, baseUrl, tenantId, false);
    }
}
