package com.aliyun.autowonder.workspace.event;

import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

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

class WorkspaceAccessRequestedListenerTest {

    private static final String PROVIDER = "DINGTALK";

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = WorkspaceAccessRequestedListener.class.getDeclaredMethod(
                "onWorkspaceAccessRequested", WorkspaceAccessRequestedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void channelNotReadySkipsQueueAndMemberLookup() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(false);

        fixture.listener.onWorkspaceAccessRequested(event());

        verify(fixture.queue, never()).enqueue(any());
        verify(fixture.memberDao, never()).listByTenant(any());
    }

    @Test
    void enqueuesOncePerAdminWithUsableIdentity() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN"),
                member(13L, "READ_WRITE")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(identity(11L, "staff-11"));
        when(fixture.identityService.find(12L, PROVIDER)).thenReturn(identity(12L, "staff-12"));

        fixture.listener.onWorkspaceAccessRequested(event());

        ArgumentCaptor<ImNotificationTask> tasks = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue, org.mockito.Mockito.times(2)).enqueue(tasks.capture());

        List<ImNotificationTask> enqueued = tasks.getAllValues();
        assertEquals(List.of(11L, 12L), enqueued.stream().map(ImNotificationTask::recipientUserId).toList());
        for (ImNotificationTask task : enqueued) {
            assertEquals(ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST, task.notificationType());
            assertEquals(100L, task.tenantId());
            assertEquals(555L, task.workitemEventId());
            // A non-zero workitemId would make the context resolver load an unrelated workitem: its
            // findById has no tenant predicate and access-request ids collide with workitem ids.
            assertEquals(0L, task.workitemId());
            assertEquals("李四", task.actorDisplayName());
            assertEquals("READ_WRITE", task.commentContentMd());
            assertFalse(task.isScheduledTaskRun());
        }
        assertEquals("WORKSPACE_ACCESS_REQUEST:555:DINGTALK:11", enqueued.get(0).notificationKey());
        assertEquals("WORKSPACE_ACCESS_REQUEST:555:DINGTALK:12", enqueued.get(1).notificationKey());
    }

    @Test
    void adminsWithoutUsableIdentityAreSkipped() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN"),
                member(13L, "ADMIN")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(null);
        when(fixture.identityService.find(12L, PROVIDER)).thenReturn(identity(12L, "  "));
        when(fixture.identityService.find(13L, PROVIDER)).thenReturn(identity(13L, "staff-13"));

        fixture.listener.onWorkspaceAccessRequested(event());

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals(13L, task.getValue().recipientUserId());
    }

    @Test
    void requesterWhoIsAlsoAdminIsNotNotifiedOfOwnRequest() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(9L, "ADMIN"),
                member(11L, "ADMIN")));
        when(fixture.identityService.find(9L, PROVIDER)).thenReturn(identity(9L, "staff-9"));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(identity(11L, "staff-11"));

        fixture.listener.onWorkspaceAccessRequested(event());

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(task.capture());
        assertEquals(11L, task.getValue().recipientUserId());
    }

    @Test
    void queueFailureForOneAdminDoesNotPropagateNorStopOthers() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(identity(11L, "staff-11"));
        when(fixture.identityService.find(12L, PROVIDER)).thenReturn(identity(12L, "staff-12"));
        doThrow(new IllegalStateException("redis unavailable")).when(fixture.queue).enqueue(any());

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessRequested(event()));
        verify(fixture.queue, org.mockito.Mockito.times(2)).enqueue(any());
    }

    @Test
    void memberLookupFailureIsSwallowed() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessRequested(event()));
        verify(fixture.queue, never()).enqueue(any());
    }

    @Test
    void nullWorkspaceNameIsTolerated() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(member(11L, "ADMIN")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(identity(11L, "staff-11"));

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessRequested(
                new WorkspaceAccessRequestedEvent(100L, 555L, 9L, "李四", "READ_WRITE", null)));
        verify(fixture.queue).enqueue(any());
    }

    private static WorkspaceAccessRequestedEvent event() {
        return new WorkspaceAccessRequestedEvent(100L, 555L, 9L, "李四", "READ_WRITE", "研发效能部");
    }

    private static WorkspaceMemberDO member(long userId, String accessLevel) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setUserId(userId);
        member.setAccessLevel(accessLevel);
        return member;
    }

    private static UserImIdentityDO identity(long userId, String externalUserId) {
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(userId);
        identity.setProvider(PROVIDER);
        identity.setExternalUserId(externalUserId);
        return identity;
    }

    private static class Fixture {
        final UserImIdentityService identityService = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService channelConfigService = mock(PlatformImChannelConfigService.class);
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
        final WorkspaceAccessRequestedListener listener =
                new WorkspaceAccessRequestedListener(identityService, channelConfigService, queue, memberDao);
    }
}
