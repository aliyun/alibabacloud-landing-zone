package com.aliyun.autowonder.workspace.event;

import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceAccessReviewedListenerTest {

    private static final String PROVIDER = "DINGTALK";

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = WorkspaceAccessReviewedListener.class.getDeclaredMethod(
                "onWorkspaceAccessReviewed", WorkspaceAccessReviewedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    /**
     * The readiness gate runs before any collaborator is touched, matching the sibling
     * WorkspaceAccessRequestedListener: an unconfigured channel must cost nothing.
     */
    @Test
    void channelNotReadySkipsQueueAndIdentityLookup() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(false);

        fixture.listener.onWorkspaceAccessReviewed(approved());

        verify(fixture.queue, never()).enqueue(any());
        verify(fixture.identityService, never()).find(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingOrBlankIdentitySkipsQueue() {
        Fixture missing = new Fixture();
        when(missing.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(missing.identityService.find(9L, PROVIDER)).thenReturn(null);

        missing.listener.onWorkspaceAccessReviewed(approved());
        verify(missing.queue, never()).enqueue(any());

        Fixture blank = new Fixture();
        when(blank.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(blank.identityService.find(9L, PROVIDER)).thenReturn(identity("   "));

        blank.listener.onWorkspaceAccessReviewed(approved());
        verify(blank.queue, never()).enqueue(any());
    }

    @Test
    void approvedNotifiesRequesterWithGrantedLevel() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.identityService.find(9L, PROVIDER)).thenReturn(identity("staff-9"));

        fixture.listener.onWorkspaceAccessReviewed(approved());

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals(ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED, task.getValue().notificationType());
        assertEquals("WORKSPACE_ACCESS_REVIEWED:555:DINGTALK:9", task.getValue().notificationKey());
        assertEquals(100L, task.getValue().tenantId());
        assertEquals(555L, task.getValue().workitemEventId());
        // A non-zero workitemId would make the context resolver load an unrelated workitem: its
        // findById has no tenant predicate and access-request ids collide with workitem ids.
        assertEquals(0L, task.getValue().workitemId());
        assertEquals(9L, task.getValue().recipientUserId());
        assertEquals("USER", task.getValue().actorType());
        assertEquals("APPROVED", task.getValue().sourceType());
        assertEquals(7L, task.getValue().actorRef());
        assertEquals("王五", task.getValue().actorDisplayName());
        assertEquals("READ_WRITE", task.getValue().commentContentMd());
        assertFalse(task.getValue().isScheduledTaskRun());
    }

    @Test
    void rejectedNotifiesRequesterWithReason() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.identityService.find(9L, PROVIDER)).thenReturn(identity("staff-9"));

        fixture.listener.onWorkspaceAccessReviewed(new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", "研发效能部", "READ_WRITE", "REJECTED", "请先完成安全培训"));

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals(ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED, task.getValue().notificationType());
        assertEquals("USER", task.getValue().actorType());
        assertEquals("REJECTED", task.getValue().sourceType());
        assertEquals("请先完成安全培训", task.getValue().commentContentMd());
    }

    @Test
    void queueFailureDoesNotPropagate() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.identityService.find(9L, PROVIDER)).thenReturn(identity("staff-9"));
        doThrow(new IllegalStateException("redis unavailable")).when(fixture.queue).enqueue(any());

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessReviewed(approved()));
    }

    @Test
    void identityLookupFailureDoesNotPropagate() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.identityService.find(9L, PROVIDER))
                .thenThrow(new IllegalStateException("staff-9-secret leaked"));

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessReviewed(approved()));
        verify(fixture.queue, never()).enqueue(any());
    }

    @Test
    void nullWorkspaceNameIsTolerated() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.identityService.find(9L, PROVIDER)).thenReturn(identity("staff-9"));

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessReviewed(new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", null, "READ_WRITE", "APPROVED", null)));
        verify(fixture.queue).enqueue(any());
    }

    private static WorkspaceAccessReviewedEvent approved() {
        return new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", "研发效能部", "READ_WRITE", "APPROVED", null);
    }

    private static UserImIdentityDO identity(String externalUserId) {
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(9L);
        identity.setProvider(PROVIDER);
        identity.setExternalUserId(externalUserId);
        return identity;
    }

    private static class Fixture {
        final UserImIdentityService identityService = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService channelConfigService = mock(PlatformImChannelConfigService.class);
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final WorkspaceAccessReviewedListener listener =
                new WorkspaceAccessReviewedListener(identityService, channelConfigService, queue);
    }
}
