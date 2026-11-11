package com.aliyun.autowonder.notification;

/** Immutable snapshot shared by the in-app notification detail and the reliable IM formatter. */
public record NotificationLink(String kind, String title, String url) {
}
