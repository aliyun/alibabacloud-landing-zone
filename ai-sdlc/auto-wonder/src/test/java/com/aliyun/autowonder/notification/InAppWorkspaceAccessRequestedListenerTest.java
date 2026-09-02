package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Constructor;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InAppWorkspaceAccessRequestedListenerTest {

    private NotifyService notifyService;
    private WorkspaceMemberDao memberDao;
    private InAppWorkspaceAccessRequestedListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        memberDao = mock(WorkspaceMemberDao.class);
        listener = new InAppWorkspaceAccessRequestedListener(notifyService, memberDao);
    }

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = InAppWorkspaceAccessRequestedListener.class.getDeclaredMethod(
                "onWorkspaceAccessRequested", WorkspaceAccessRequestedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void notifiesAllAdminsWithRequestFields() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN"),
                member(13L, "READ_ONLY")));

        listener.onWorkspaceAccessRequested(event());

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService, times(2)).notify(captor.capture());

        assertEquals(List.of(List.of(11L), List.of(12L)),
                captor.getAllValues().stream().map(NotifyEvent::getRecipientIds).toList());
        NotifyEvent sent = captor.getAllValues().get(0);
        assertEquals(100L, sent.getTenantId());
        assertEquals("WORKSPACE_ACCESS_REQUEST", sent.getType());
        assertEquals("WORKSPACE_ACCESS_REQUEST", sent.getRefType());
        assertEquals(555L, sent.getRefId());
        assertEquals("/settings/members?tab=requests", sent.getLink());
        assertEquals("有新的权限申请", sent.getTitle());
        assertEquals("李四 申请加入「研发效能部」，申请权限：读写", sent.getContent());
    }

    @Test
    void requesterWhoIsAlsoAdminIsExcluded() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(
                member(9L, "ADMIN"),
                member(11L, "ADMIN")));

        listener.onWorkspaceAccessRequested(event());

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertEquals(List.of(11L), captor.getValue().getRecipientIds());
    }

    @Test
    void noAdminsSkipsNotify() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(13L, "READ_WRITE")));

        listener.onWorkspaceAccessRequested(event());

        verify(notifyService, never()).notify(any());
    }

    @Test
    void nullWorkspaceNameIsTolerated() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(11L, "ADMIN")));

        assertDoesNotThrow(() -> listener.onWorkspaceAccessRequested(
                new WorkspaceAccessRequestedEvent(100L, 555L, 9L, "李四", "READ_WRITE", null)));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertEquals("李四 申请加入「未命名工作空间」，申请权限：读写", captor.getValue().getContent());
    }

    @Test
    void notifyFailureDoesNotPropagate() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(11L, "ADMIN")));
        doThrow(new RuntimeException("db error")).when(notifyService).notify(any());

        assertDoesNotThrow(() -> listener.onWorkspaceAccessRequested(event()));
    }

    /**
     * NotifyService.notify has no per-recipient guard around its insert, and this listener runs
     * AFTER_COMMIT, so a recipient that blows up must not cost the admins behind it their bell row:
     * there is no retry or compensation that would ever deliver it.
     */
    @Test
    void oneFailingRecipientDoesNotStopTheRemainingRecipients() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN"),
                member(13L, "ADMIN")));
        doThrow(new RuntimeException("db error")).doNothing().when(notifyService).notify(any());

        assertDoesNotThrow(() -> listener.onWorkspaceAccessRequested(event()));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService, times(3)).notify(captor.capture());
        assertEquals(List.of(List.of(11L), List.of(12L), List.of(13L)),
                captor.getAllValues().stream().map(NotifyEvent::getRecipientIds).toList());
    }

    /**
     * The bell channel must be completely independent of the DingTalk channel: this listener must not
     * take any IM dependency at all, otherwise an unconfigured/unreachable DingTalk would silently
     * suppress persistent in-app notifications too.
     */
    @Test
    void inAppPathHasNoDingTalkChannelOrIdentityDependency() {
        Constructor<?>[] constructors = InAppWorkspaceAccessRequestedListener.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        for (Class<?> parameterType : constructors[0].getParameterTypes()) {
            String name = parameterType.getName();
            assertFalse(name.contains("PlatformImChannelConfigService"),
                    "in-app bell must not be gated on IM channel readiness: " + name);
            assertFalse(name.contains("UserImIdentityService"),
                    "in-app bell must not be gated on a DingTalk identity: " + name);
            assertFalse(name.contains("ImNotificationQueue"),
                    "in-app bell must not depend on the IM queue: " + name);
        }
    }

    /**
     * Companion to the reflection guard above: with the DingTalk channel entirely absent (this test
     * constructs no IM collaborators), the bell notification still goes out.
     */
    @Test
    void notifiesAdminsEvenWhenDingTalkChannelIsNotReady() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(11L, "ADMIN")));

        listener.onWorkspaceAccessRequested(event());

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertEquals(List.of(11L), captor.getValue().getRecipientIds());
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
}
