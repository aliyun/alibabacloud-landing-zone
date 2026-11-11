package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知主题必须只认 principal 自己的工作空间与本人 ID：伪造他人主题、缺位、溢出都要拒绝。
 */
class NotificationRealtimeAuthorizationServiceTest {

    private final NotificationRealtimeAuthorizationService service = new NotificationRealtimeAuthorizationService();

    @Test
    void supportsOnlyNotificationChannels() {
        assertTrue(service.supports("notification:10002:10018"));
        assertFalse(service.supports("conversation:1"));
        assertFalse(service.supports("notification"));
        assertFalse(service.supports(null));
    }

    @Test
    void allowsThePrincipalOwnRecipientChannel() {
        assertTrue(service.authorize(10002L, 10018L, "notification:10002:10018"));
    }

    @Test
    void rejectsAnotherRecipientInTheSameWorkspace() {
        assertFalse(service.authorize(10002L, 10018L, "notification:10002:10019"));
    }

    @Test
    void rejectsAnotherWorkspaceForTheSameRecipient() {
        assertFalse(service.authorize(10002L, 10018L, "notification:10003:10018"));
    }

    @Test
    void rejectsMalformedOrOverflowingIdentifiers() {
        assertFalse(service.authorize(10002L, 10018L, "notification:"));
        assertFalse(service.authorize(10002L, 10018L, "notification:10002"));
        assertFalse(service.authorize(10002L, 10018L, "notification:10002:"));
        assertFalse(service.authorize(10002L, 10018L, "notification::10018"));
        assertFalse(service.authorize(10002L, 10018L, "notification:abc:10018"));
        assertFalse(service.authorize(10002L, 10018L, "notification:10002:abc"));
        assertFalse(service.authorize(10002L, 10018L, "notification:10002:10018:9"));
        assertFalse(service.authorize(10002L, 10018L, "notification:999999999999999999999:10018"));
    }

    @Test
    void rejectsNonPositivePrincipalOrForeignPrefix() {
        assertFalse(service.authorize(0L, 10018L, "notification:0:10018"));
        assertFalse(service.authorize(10002L, 0L, "notification:10002:0"));
        assertFalse(service.authorize(10002L, 10018L, "dispatch:10002:10018"));
    }
}
