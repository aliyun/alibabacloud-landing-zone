package com.aliyun.autowonder.im.notification;

public record ImNotificationMessageContext(
        String orgName,
        String statusName,
        String baseUrl,
        long tenantId) {
}
