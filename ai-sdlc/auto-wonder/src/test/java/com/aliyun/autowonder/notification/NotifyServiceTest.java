package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicLong;

class NotifyServiceTest {

    private NotificationDao notificationDao;
    private NotifyPrefDao prefDao;
    private NotifyChannel inAppChannel;
    private NotifyChannel dingTalkChannel;
    private NotifyService service;

    @BeforeEach
    void setUp() {
        notificationDao = mock(NotificationDao.class);
        prefDao = mock(NotifyPrefDao.class);
        inAppChannel = mock(NotifyChannel.class);
        dingTalkChannel = mock(NotifyChannel.class);
        when(inAppChannel.name()).thenReturn("inApp");
        when(dingTalkChannel.name()).thenReturn("dingtalk");
        when(inAppChannel.deliver(any())).thenReturn(true);
        when(dingTalkChannel.deliver(any())).thenReturn(true);
        AtomicLong idSeq = new AtomicLong(1);
        doAnswer(inv -> { ((NotificationDO) inv.getArgument(0)).setId(idSeq.getAndIncrement()); return null; })
                .when(notificationDao).insert(any());
        service = new NotifyService(notificationDao, prefDao,
                List.of(inAppChannel, dingTalkChannel));
    }

    @Test
    void platformScopedNotificationOnlyUsesInAppAndSkipsWorkspacePreferences() {
        NotifyEvent event = new NotifyEvent();
        event.setScope(NotificationScope.PLATFORM);
        event.setTenantId(NotificationScope.PLATFORM_TENANT_ID);
        event.setType("USER_REGISTRATION_REVIEW");
        event.setTitle("有新用户注册，请审核");
        event.setContent("用户 A 提交了注册申请");
        event.setRecipientIds(List.of(10L));

        service.notify(event);

        ArgumentCaptor<NotificationDO> inserted = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(inserted.capture());
        assertEquals(NotificationScope.PLATFORM, inserted.getValue().getScope());
        verify(inAppChannel).deliver(any());
        verify(dingTalkChannel, never()).deliver(any());
        verify(prefDao, never()).findByUserAndType(anyLong(), anyLong(), anyString());
    }

    @Test
    void notifyCreatesNotificationAndDeliversToAllChannels() {
        when(prefDao.findByUserAndType(1L, 10L, "DISPATCH_ALERT")).thenReturn(null);

        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("DISPATCH_ALERT");
        event.setTitle("Dispatch failed");
        event.setContent("workitem 42 dispatch failed");
        event.setLink("/workitems/42");
        event.setRecipientIds(List.of(10L));

        service.notify(event);

        verify(notificationDao).insert(any());
        verify(inAppChannel).deliver(any());
        verify(dingTalkChannel).deliver(any());
        verify(notificationDao).updateChannels(eq(1L), eq(1L), anyString());
    }

    @Test
    void notifyRespectsUserPrefDisablingDingtalk() {
        NotifyPrefDO pref = new NotifyPrefDO();
        pref.setInApp(1);
        pref.setDingtalk(0);
        when(prefDao.findByUserAndType(1L, 10L, "MEMORY_REVIEW")).thenReturn(pref);

        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("MEMORY_REVIEW");
        event.setTitle("New memory");
        event.setRecipientIds(List.of(10L));

        service.notify(event);

        verify(inAppChannel).deliver(any());
        verify(dingTalkChannel, never()).deliver(any());
    }

    @Test
    void notifySkipsInAppDeliveryWhenTheRecipientDisabledIt() {
        // 关闭某类 inApp 后既不进未读列表也不实时推送；持久化与钉钉偏好不受影响。
        NotifyPrefDO pref = new NotifyPrefDO();
        pref.setInApp(0);
        pref.setDingtalk(1);
        when(prefDao.findByUserAndType(1L, 10L, "AI_DONE")).thenReturn(pref);

        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("AI_DONE");
        event.setTitle("AI done");
        event.setRecipientIds(List.of(10L));

        service.notify(event);

        verify(inAppChannel, never()).deliver(any());
        verify(dingTalkChannel).deliver(any());
        verify(notificationDao).insert(any());
    }

    @Test
    void notifyMultipleRecipients() {
        when(prefDao.findByUserAndType(anyLong(), anyLong(), anyString())).thenReturn(null);

        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("AI_DONE");
        event.setTitle("AI done");
        event.setRecipientIds(List.of(10L, 20L));

        service.notify(event);

        verify(notificationDao, times(2)).insert(any());
    }

    @Test
    void notifyHandlesChannelExceptionGracefully() {
        when(prefDao.findByUserAndType(anyLong(), anyLong(), anyString())).thenReturn(null);
        when(inAppChannel.deliver(any())).thenReturn(true);
        when(dingTalkChannel.deliver(any())).thenThrow(new RuntimeException("network error"));

        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("ALERT");
        event.setTitle("test");
        event.setRecipientIds(List.of(10L));

        assertDoesNotThrow(() -> service.notify(event));
        verify(notificationDao).insert(any());
        verify(notificationDao).updateChannels(eq(1L), eq(1L), contains("error:network error"));
    }

    @Test
    void notifyNullRecipientsDoesNothing() {
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("TEST");
        event.setRecipientIds(null);

        assertDoesNotThrow(() -> service.notify(event));
        verify(notificationDao, never()).insert(any());
    }

    @Test
    void notifyEmptyRecipientsDoesNothing() {
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("TEST");
        event.setRecipientIds(List.of());

        assertDoesNotThrow(() -> service.notify(event));
        verify(notificationDao, never()).insert(any());
    }

    @Test
    void unreadCountDelegates() {
        when(notificationDao.countUnread(1L, 10L)).thenReturn(5);
        assertEquals(5, service.unreadCount(1L, 10L));
    }

    @Test
    void markReadDelegates() {
        service.markRead(1L, 1L, 10L);
        verify(notificationDao).markRead(1L, 1L, 10L);
    }

    @Test
    void markAllReadDelegates() {
        service.markAllRead(1L, 10L);
        verify(notificationDao).markAllRead(1L, 10L);
    }

    @Test
    void deletePassesTenantAndRecipientSoOwnershipIsCheckedInSql() {
        when(notificationDao.delete(5L, 1L, 10L)).thenReturn(1);

        service.delete(5L, 1L, 10L);

        verify(notificationDao).delete(5L, 1L, 10L);
    }

    @Test
    void deleteThrowsNotFoundWhenNoRowIsAffected() {
        // DAO 返回 0 行意味着通知不存在或属于他人，静默成功会掩盖越权删除。
        when(notificationDao.delete(5L, 1L, 10L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.delete(5L, 1L, 10L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void notifyPersistsImmutableRelatedLinkSnapshot() {
        List<NotificationLink> source = new ArrayList<>();
        source.add(new NotificationLink("SUBMITTED", "新工单", "/workitems/55?workspaceId=1"));
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("DUPLICATE_INTENT");
        event.setTitle("重复提醒");
        event.setRecipientIds(List.of(10L));
        event.setRelatedLinks(source);

        service.notify(event);
        // 生产者拿到事件后继续改动原集合，不能影响已落库的快照。
        source.add(new NotificationLink("DUPLICATE", "迟到修改", "/workitems/2?workspaceId=1"));

        ArgumentCaptor<NotificationDO> row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertEquals(List.of(new NotificationLink("SUBMITTED", "新工单", "/workitems/55?workspaceId=1")),
                NotificationLinkJson.decode(row.getValue().getRelatedLinksJson()));
        assertThrows(UnsupportedOperationException.class,
                () -> event.getRelatedLinks().add(new NotificationLink("DUPLICATE", "x", "/workitems/2")));
    }

    @Test
    void notifyWithoutRelatedLinksKeepsTheSnapshotColumnEmpty() {
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(1L);
        event.setType("DISPATCH_ALERT");
        event.setTitle("Dispatch failed");
        event.setRecipientIds(List.of(10L));

        service.notify(event);

        ArgumentCaptor<NotificationDO> row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationDao).insert(row.capture());
        assertNull(row.getValue().getRelatedLinksJson());
    }
}
