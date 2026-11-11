package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.json.JSON;

import java.util.List;

/** Single conversion point between the nullable JSON column and the typed link snapshot. */
public final class NotificationLinkJson {

    private NotificationLinkJson() {
    }

    public static String encode(List<NotificationLink> links) {
        return links == null ? null : JSON.toJSONString(links);
    }

    public static List<NotificationLink> decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return List.copyOf(JSON.parseArray(json, NotificationLink.class));
    }
}
