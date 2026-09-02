package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessCancelledEvent;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InAppWorkspaceAccessCancelledListenerTest {

    private NotifyService notifyService;
    private WorkspaceMemberDao memberDao;
    private InAppWorkspaceAccessCancelledListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        memberDao = mock(WorkspaceMemberDao.class);
        listener = new InAppWorkspaceAccessCancelledListener(notifyService, memberDao);
    }

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = InAppWorkspaceAccessCancelledListener.class.getDeclaredMethod(
                "onWorkspaceAccessCancelled", WorkspaceAccessCancelledEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void notifiesAllAdminsWithWhoWhenAndDeletion() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN"),
                member(13L, "READ_ONLY")));

        listener.onWorkspaceAccessCancelled(event());

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
        assertEquals("权限申请已撤销", sent.getTitle());
        // FR-6: who, when, and that the record will be deleted. The timestamp is clock-derived,
        // so the invariant parts before and after it are pinned instead of the whole sentence.
        assertTrue(sent.getContent().startsWith("李四 于 "), "got: " + sent.getContent());
        assertTrue(sent.getContent().endsWith(" 撤销了加入「研发效能部」的申请，申请记录将删除"),
                "got: " + sent.getContent());
    }

    @Test
    void requesterWhoIsAlsoAdminIsExcluded() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(
                member(9L, "ADMIN"),
                member(11L, "ADMIN")));

        listener.onWorkspaceAccessCancelled(event());

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertEquals(List.of(11L), captor.getValue().getRecipientIds());
    }

    @Test
    void noAdminsSkipsNotify() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(13L, "READ_WRITE")));

        listener.onWorkspaceAccessCancelled(event());

        verify(notifyService, never()).notify(any());
    }

    @Test
    void nullWorkspaceNameIsTolerated() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(11L, "ADMIN")));

        assertDoesNotThrow(() -> listener.onWorkspaceAccessCancelled(
                new WorkspaceAccessCancelledEvent(100L, 555L, 9L, "李四", "READ_WRITE", null)));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertTrue(captor.getValue().getContent().contains("撤销了加入「未命名工作空间」的申请"),
                "got: " + captor.getValue().getContent());
    }

    @Test
    void notifyFailureDoesNotPropagate() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(member(11L, "ADMIN")));
        doThrow(new RuntimeException("db error")).when(notifyService).notify(any());

        assertDoesNotThrow(() -> listener.onWorkspaceAccessCancelled(event()));
    }

    @Test
    void oneFailingRecipientDoesNotStopTheRemainingRecipients() {
        when(memberDao.listByTenant(100L)).thenReturn(List.of(
                member(11L, "ADMIN"),
                member(12L, "ADMIN"),
                member(13L, "ADMIN")));
        doThrow(new RuntimeException("db error")).doNothing().when(notifyService).notify(any());

        assertDoesNotThrow(() -> listener.onWorkspaceAccessCancelled(event()));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService, times(3)).notify(captor.capture());
        assertEquals(List.of(List.of(11L), List.of(12L), List.of(13L)),
                captor.getAllValues().stream().map(NotifyEvent::getRecipientIds).toList());
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
}
