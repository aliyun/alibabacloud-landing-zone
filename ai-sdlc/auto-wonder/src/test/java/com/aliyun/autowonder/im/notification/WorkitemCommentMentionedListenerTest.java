package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemCommentMentionedListenerTest {

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = WorkitemCommentMentionedListener.class.getDeclaredMethod(
                "onWorkitemCommentMentioned", WorkitemCommentMentionedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void eligibleMentionQueuesDingTalkTaskWithCommentType() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        fixture.listener.onWorkitemCommentMentioned(event());

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals("COMMENT_MENTION:7001:DINGTALK:99", task.getValue().notificationKey());
        assertEquals(7001L, task.getValue().workitemEventId());
        assertEquals(100L, task.getValue().tenantId());
        assertEquals(500L, task.getValue().workitemId());
        assertEquals(99L, task.getValue().recipientUserId());
        assertEquals("AGENT", task.getValue().actorType());
        assertEquals(40014L, task.getValue().actorRef());
        assertEquals("AW项目管理员", task.getValue().actorDisplayName());
        assertEquals("COMMENT_MENTION", task.getValue().notificationType());
        assertEquals("@李四 请确认", task.getValue().commentContentMd());
    }

    @Test
    void missingIdentityOrChannelSkipsQueue() {
        Fixture missingIdentity = new Fixture();
        when(missingIdentity.identityService.find(99L, "DINGTALK")).thenReturn(null);
        when(missingIdentity.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        missingIdentity.listener.onWorkitemCommentMentioned(event());

        verify(missingIdentity.queue, never()).enqueue(any());

        Fixture channelDown = new Fixture();
        when(channelDown.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(channelDown.channelConfigService.isReady("DINGTALK")).thenReturn(false);

        channelDown.listener.onWorkitemCommentMentioned(event());

        verify(channelDown.queue, never()).enqueue(any());
    }

    @Test
    void enqueueAndLookupFailuresAreSwallowed() {
        Fixture enqueueFailure = new Fixture();
        when(enqueueFailure.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(enqueueFailure.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable")).when(enqueueFailure.queue).enqueue(any());

        assertDoesNotThrow(() -> enqueueFailure.listener.onWorkitemCommentMentioned(event()));

        Fixture lookupFailure = new Fixture();
        when(lookupFailure.identityService.find(99L, "DINGTALK"))
                .thenThrow(new IllegalStateException("secret staff-99-secret-value leaked"));

        assertDoesNotThrow(() -> lookupFailure.listener.onWorkitemCommentMentioned(event()));
        verify(lookupFailure.queue, never()).enqueue(any());
    }

    private static WorkitemCommentMentionedEvent event() {
        return new WorkitemCommentMentionedEvent(
                100L,
                500L,
                "修复线上缺陷",
                7001L,
                99L,
                "AGENT",
                40014L,
                "AW项目管理员",
                "rid-1",
                "@李四 请确认");
    }

    private static UserImIdentityDO identity() {
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(99L);
        identity.setProvider("DINGTALK");
        identity.setExternalUserId("staff-99-secret-value");
        return identity;
    }

    private static class Fixture {
        final UserImIdentityService identityService = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService channelConfigService = mock(PlatformImChannelConfigService.class);
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final WorkitemCommentMentionedListener listener =
                new WorkitemCommentMentionedListener(identityService, channelConfigService, queue);
    }
}
