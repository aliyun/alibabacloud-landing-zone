package com.aliyun.autowonder.im;

import java.util.Locale;

/** Platform IM configs are split by purpose; notification and shared group chat never share a row. */
public enum ImChannelPurpose {
    NOTIFY,
    GROUP_CHAT;

    public static ImChannelPurpose from(String value) {
        if (value == null || value.isBlank()) {
            return NOTIFY;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ImChannelPurpose purpose : values()) {
            if (purpose.name().equals(normalized)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("unsupported IM channel purpose: " + value);
    }
}
