package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InAppWorkspaceAccessReviewedListenerTest {

    private NotifyService notifyService;
    private InAppWorkspaceAccessReviewedListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        listener = new InAppWorkspaceAccessReviewedListener(notifyService);
    }

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = InAppWorkspaceAccessReviewedListener.class.getDeclaredMethod(
                "onWorkspaceAccessReviewed", WorkspaceAccessReviewedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
        assertFalse(annotation.fallbackExecution());
    }

    @Test
    void approvedNotifiesRequesterWithWorkspaceLink() {
        listener.onWorkspaceAccessReviewed(approved());

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        NotifyEvent sent = captor.getValue();
        assertEquals(100L, sent.getTenantId());
        assertEquals("WORKSPACE_ACCESS_REVIEWED", sent.getType());
        assertEquals("WORKSPACE_ACCESS_REQUEST", sent.getRefType());
        assertEquals(555L, sent.getRefId());
        assertEquals("/workspaces", sent.getLink());
        assertEquals(List.of(9L), sent.getRecipientIds());
        assertEquals("权限申请已通过", sent.getTitle());
        assertTrue(sent.getContent().contains("研发效能部"));
        assertTrue(sent.getContent().contains("读写"));
        assertTrue(sent.getContent().contains("王五"));
    }

    @Test
    void rejectedNotifiesRequesterWithReason() {
        listener.onWorkspaceAccessReviewed(new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", "研发效能部", "READ_WRITE", "REJECTED", "请先完成安全培训"));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        NotifyEvent sent = captor.getValue();
        assertEquals("WORKSPACE_ACCESS_REVIEWED", sent.getType());
        assertEquals("WORKSPACE_ACCESS_REQUEST", sent.getRefType());
        assertEquals(555L, sent.getRefId());
        assertEquals("/workspaces", sent.getLink());
        assertEquals(List.of(9L), sent.getRecipientIds());
        assertEquals("权限申请被拒绝", sent.getTitle());
        assertTrue(sent.getContent().contains("请先完成安全培训"));
    }

    @Test
    void rejectedWithoutReasonOmitsReasonText() {
        listener.onWorkspaceAccessReviewed(new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", "研发效能部", "READ_WRITE", "REJECTED", null));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        assertEquals("权限申请被拒绝", captor.getValue().getTitle());
        assertFalse(captor.getValue().getContent().contains("原因"));
    }

    @Test
    void nullWorkspaceNameIsTolerated() {
        assertDoesNotThrow(() -> listener.onWorkspaceAccessReviewed(new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", null, "READ_WRITE", "APPROVED", null)));

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertEquals("你加入「未命名工作空间」的申请已通过，授予权限：读写，审批人：王五",
                captor.getValue().getContent());
    }

    @Test
    void notifyFailureDoesNotPropagate() {
        doThrow(new RuntimeException("db error")).when(notifyService).notify(any());

        assertDoesNotThrow(() -> listener.onWorkspaceAccessReviewed(approved()));
    }

    /**
     * The bell channel must be completely independent of the DingTalk channel: this listener must not
     * take any IM dependency at all, otherwise an unconfigured/unreachable DingTalk would silently
     * suppress persistent in-app notifications too.
     */
    @Test
    void inAppPathHasNoDingTalkChannelOrIdentityDependency() {
        Constructor<?>[] constructors = InAppWorkspaceAccessReviewedListener.class.getDeclaredConstructors();
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
    void notifiesRequesterEvenWhenDingTalkChannelIsNotReady() {
        listener.onWorkspaceAccessReviewed(approved());

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        assertEquals(List.of(9L), captor.getValue().getRecipientIds());
    }

    private static WorkspaceAccessReviewedEvent approved() {
        return new WorkspaceAccessReviewedEvent(
                100L, 555L, 9L, 7L, "王五", "研发效能部", "READ_WRITE", "APPROVED", null);
    }
}
