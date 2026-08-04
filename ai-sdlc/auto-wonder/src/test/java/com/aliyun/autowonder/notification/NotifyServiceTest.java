package com.aliyun.autowonder.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
