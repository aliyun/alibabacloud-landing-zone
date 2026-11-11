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

class WorkspaceAccessCancelledListenerTest {

    private static final String PROVIDER = "DINGTALK";

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = WorkspaceAccessCancelledListener.class.getDeclaredMethod(
                "onWorkspaceAccessCancelled", WorkspaceAccessCancelledEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void channelNotReadySkipsQueueAndMemberLookup() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(false);

        fixture.listener.onWorkspaceAccessCancelled(event());

        verify(fixture.queue, never()).enqueue(any());
        verify(fixture.memberDao, never()).listByTenant(any());
    }

    @Test
    void queuesCancellationForEveryAdminExceptRequesterWithIndependentKey() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(9L, "ADMIN"),
                member(11L, "ADMIN"),
                member(12L, "READ_WRITE")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(identity(11L, "ding-11"));

        fixture.listener.onWorkspaceAccessCancelled(event());

        ArgumentCaptor<ImNotificationTask> captor = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(captor.capture());
        ImNotificationTask task = captor.getValue();
        assertEquals("WORKSPACE_ACCESS_CANCELLED:555:DINGTALK:11", task.notificationKey());
        assertEquals(0L, task.workitemId());
        assertEquals(ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST, task.notificationType());
        assertEquals(ImNotificationTask.SOURCE_ACCESS_CANCELLED, task.sourceType());
        assertEquals("李四", task.actorDisplayName());
        assertEquals(9L, task.actorRef());
        assertEquals("READ_WRITE", task.commentContentMd());
        assertFalse(task.isScheduledTaskRun());
    }

    @Test
    void adminsWithoutUsableIdentityAreSkipped() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"), member(12L, "ADMIN"), member(13L, "ADMIN")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(null);
        when(fixture.identityService.find(12L, PROVIDER)).thenReturn(identity(12L, "  "));
        when(fixture.identityService.find(13L, PROVIDER)).thenReturn(identity(13L, "ding-13"));

        fixture.listener.onWorkspaceAccessCancelled(event());

        ArgumentCaptor<ImNotificationTask> captor = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(fixture.queue).enqueue(captor.capture());
        assertEquals(13L, captor.getValue().recipientUserId());
    }

    @Test
    void noAdminsMeansNoQueueEntry() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(member(12L, "READ_WRITE")));

        fixture.listener.onWorkspaceAccessCancelled(event());

        verify(fixture.queue, never()).enqueue(any());
    }

    @Test
    void queueFailureForOneAdminDoesNotPropagateNorStopOthers() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"), member(12L, "ADMIN")));
        when(fixture.identityService.find(11L, PROVIDER)).thenReturn(identity(11L, "ding-11"));
        when(fixture.identityService.find(12L, PROVIDER)).thenReturn(identity(12L, "ding-12"));
        doThrow(new IllegalStateException("redis unavailable")).when(fixture.queue).enqueue(any());

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessCancelled(event()));
        verify(fixture.queue, org.mockito.Mockito.times(2)).enqueue(any());
    }

    @Test
    void memberLookupFailureIsSwallowed() {
        Fixture fixture = new Fixture();
        when(fixture.channelConfigService.isReady(PROVIDER)).thenReturn(true);
        when(fixture.memberDao.listByTenant(100L)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> fixture.listener.onWorkspaceAccessCancelled(event()));
        verify(fixture.queue, never()).enqueue(any());
    }

    private static WorkspaceAccessCancelledEvent event() {
        return new WorkspaceAccessCancelledEvent(100L, 555L, 9L, "李四", "READ_WRITE", "研发效能部");
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
        { when(channelConfigService.selectedProvider()).thenReturn("DINGTALK"); }
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
        final WorkspaceAccessCancelledListener listener =
                new WorkspaceAccessCancelledListener(identityService, channelConfigService, queue, memberDao);
    }
}
