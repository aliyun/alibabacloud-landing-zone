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

class WorkitemHumanAssignedListenerTest {

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = WorkitemHumanAssignedListener.class.getDeclaredMethod(
                "onWorkitemHumanAssigned", WorkitemHumanAssignedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void eligibleRecipientQueuesDingTalkTaskWithStableNotificationKey() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        fixture.listener.onWorkitemHumanAssigned(event());

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals("9001:DINGTALK:99", task.getValue().notificationKey());
        assertEquals(9001L, task.getValue().workitemEventId());
        assertEquals(100L, task.getValue().tenantId());
        assertEquals(500L, task.getValue().workitemId());
        assertEquals(99L, task.getValue().recipientUserId());
        assertEquals("AGENT", task.getValue().actorType());
        assertEquals(40014L, task.getValue().actorRef());
        assertEquals("AW开发数字人", task.getValue().actorDisplayName());
        assertEquals("rid-1", task.getValue().requestId());
        assertEquals("修复线上缺陷", task.getValue().workitemTitle());
    }

    @Test
    void missingIdentityOrChannelSkipsQueue() {
        Fixture missingIdentity = new Fixture();
        when(missingIdentity.identityService.find(99L, "DINGTALK")).thenReturn(null);
        when(missingIdentity.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        missingIdentity.listener.onWorkitemHumanAssigned(event());

        verify(missingIdentity.queue, never()).enqueue(any());

        Fixture channelDown = new Fixture();
        when(channelDown.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(channelDown.channelConfigService.isReady("DINGTALK")).thenReturn(false);

        channelDown.listener.onWorkitemHumanAssigned(event());

        verify(channelDown.queue, never()).enqueue(any());
    }

    @Test
    void enqueueFailureIsSwallowed() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable")).when(fixture.queue).enqueue(any());

        fixture.listener.onWorkitemHumanAssigned(event());

        verify(fixture.queue).enqueue(any());
    }

    @Test
    void identityLookupFailureIsSwallowedWithoutQueueing() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(99L, "DINGTALK"))
                .thenThrow(new IllegalStateException("secret staff-99-secret-value leaked"));

        assertDoesNotThrow(() -> fixture.listener.onWorkitemHumanAssigned(event()));

        verify(fixture.queue, never()).enqueue(any());
    }

    @Test
    void channelReadinessFailureIsSwallowedWithoutQueueing() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(99L, "DINGTALK")).thenReturn(identity());
        when(fixture.channelConfigService.isReady("DINGTALK"))
                .thenThrow(new IllegalStateException("secret staff-99-secret-value leaked"));

        assertDoesNotThrow(() -> fixture.listener.onWorkitemHumanAssigned(event()));

        verify(fixture.queue, never()).enqueue(any());
    }

    private static WorkitemHumanAssignedEvent event() {
        return new WorkitemHumanAssignedEvent(
                100L,
                500L,
                "修复线上缺陷",
                9001L,
                99L,
                "AGENT",
                40014L,
                "AW开发数字人",
                "rid-1");
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
        final WorkitemHumanAssignedListener listener =
                new WorkitemHumanAssignedListener(identityService, channelConfigService, queue);
    }
}
