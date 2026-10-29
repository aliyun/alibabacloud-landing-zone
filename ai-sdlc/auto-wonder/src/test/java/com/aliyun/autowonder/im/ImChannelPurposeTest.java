package com.aliyun.autowonder.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImChannelPurposeTest {

    @Test
    void defaultsToNotificationPurpose() {
        assertEquals(ImChannelPurpose.NOTIFY, ImChannelPurpose.from(null));
        assertEquals(ImChannelPurpose.NOTIFY, ImChannelPurpose.from(""));
        assertEquals(ImChannelPurpose.NOTIFY, ImChannelPurpose.from("  "));
        assertEquals(ImChannelPurpose.NOTIFY, ImChannelPurpose.from("notify"));
    }

    @Test
    void parsesGroupChatCaseInsensitively() {
        assertEquals(ImChannelPurpose.GROUP_CHAT, ImChannelPurpose.from("group_chat"));
        assertEquals(ImChannelPurpose.GROUP_CHAT, ImChannelPurpose.from(" GROUP_CHAT "));
    }

    @Test
    void rejectsUnknownPurpose() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ImChannelPurpose.from("BROADCAST"));
        assertTrue(error.getMessage().contains("BROADCAST"));
    }
}
