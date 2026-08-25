package com.aliyun.autowonder.im.notification;

public record ImNotificationMessageContext(
        String workspaceName,
        String statusName,
        String baseUrl,
        long tenantId) {
}
