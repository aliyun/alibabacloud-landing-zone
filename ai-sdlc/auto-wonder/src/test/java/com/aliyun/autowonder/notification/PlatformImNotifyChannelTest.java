package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.*;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import com.aliyun.autowonder.setting.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformImNotifyChannelTest {
    @Test
    void projectNotificationsReuseSelectedPlatformBotAndIdentity() {
        var configs = mock(PlatformImChannelConfigService.class);
        var identities = mock(UserImIdentityService.class);
        var registry = mock(ImProviderRegistry.class);
        var settings = mock(SystemSettingService.class);
        var provider = mock(ImProvider.class);
        when(configs.selectedProvider()).thenReturn("FEISHU");
        when(configs.isReady("FEISHU")).thenReturn(true);
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("true");
        var identity = new UserImIdentityDO(); identity.setExternalUserId("fs-user");
        when(identities.find(2L, "FEISHU")).thenReturn(identity);
        when(registry.require("FEISHU")).thenReturn(provider);
        var channel = new PlatformImNotifyChannel(configs, identities, registry, settings, mock(com.aliyun.autowonder.im.notification.ImNotificationQueue.class));
        var notification = new NotificationDO(); notification.setTenantId(1L); notification.setRecipientId(2L);
        notification.setTitle("新通知"); notification.setContent("内容");
        assertEquals("feishu", channel.name());
        assertTrue(channel.deliver(notification));
        var sent = ArgumentCaptor.forClass(ImSendCommand.class);
        verify(provider).send(sent.capture());
        assertEquals("FEISHU", sent.getValue().provider());
        assertEquals("fs-user", sent.getValue().externalUserId());
        clearInvocations(registry);
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("false");
        assertFalse(channel.deliver(notification));
        verifyNoInteractions(registry);
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("true");
        notification.setType("WORKITEM_ASSIGNED");
        assertTrue(channel.deliver(notification));
        verifyNoInteractions(registry); // Assignment delivery belongs exclusively to the IM queue.
    }

    @Test
    void duplicateIntentEnqueuesReliableImWithLocaleAndLinks() {
        var configs = mock(PlatformImChannelConfigService.class);
        var identities = mock(UserImIdentityService.class);
        var registry = mock(ImProviderRegistry.class);
        var settings = mock(SystemSettingService.class);
        var queue = mock(ImNotificationQueue.class);
        when(configs.selectedProvider()).thenReturn("DINGTALK");
        var channel = new PlatformImNotifyChannel(configs, identities, registry, settings, queue);
        var notification = new NotificationDO();
        notification.setId(88L);
        notification.setTenantId(1L);
        notification.setRecipientId(2L);
        notification.setRefId(55L);
        notification.setType("DUPLICATE_INTENT");
        notification.setTitle("重复提醒");
        notification.setContent("新旧标题");
        notification.setRecipientLocale("en-US");
        notification.setRelatedLinksJson("[{\"kind\":\"SUBMITTED\",\"title\":\"new\",\"url\":\"/workitems/55?workspaceId=1\"},"
                + "{\"kind\":\"DUPLICATE\",\"title\":\"old\",\"url\":\"/workitems/2?workspaceId=1\"}]");

        assertTrue(channel.deliver(notification));

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(queue).enqueue(task.capture());
        assertEquals("duplicate-intent:88:DINGTALK:2", task.getValue().notificationKey());
        assertEquals("DUPLICATE_INTENT", task.getValue().notificationType());
        assertEquals(55L, task.getValue().workitemId());
        assertEquals(2L, task.getValue().recipientUserId());
        assertEquals("en-US", task.getValue().recipientLocale());
        assertEquals("新旧标题", task.getValue().commentContentMd());
        assertEquals(List.of(
                new NotificationLink("SUBMITTED", "new", "/workitems/55?workspaceId=1"),
                new NotificationLink("DUPLICATE", "old", "/workitems/2?workspaceId=1")),
                task.getValue().relatedLinks());
        // 入队分支不碰 provider 与身份，真实检查留给 worker；也不在请求线程同步外呼。
        verifyNoInteractions(registry);
        verifyNoInteractions(identities);
        verifyNoInteractions(settings);
    }

    @Test
    void duplicateIntentWithoutSnapshotStillEnqueuesForTheFormatterFallback() {
        var configs = mock(PlatformImChannelConfigService.class);
        var queue = mock(ImNotificationQueue.class);
        when(configs.selectedProvider()).thenReturn("DINGTALK");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class),
                mock(ImProviderRegistry.class), mock(SystemSettingService.class), queue);
        var notification = new NotificationDO();
        notification.setId(89L);
        notification.setTenantId(1L);
        notification.setRecipientId(2L);
        notification.setRefId(56L);
        notification.setType("DUPLICATE_INTENT");
        notification.setContent("纯文本摘要");

        assertTrue(channel.deliver(notification));

        ArgumentCaptor<ImNotificationTask> task = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(queue).enqueue(task.capture());
        assertNull(task.getValue().relatedLinks());
        assertEquals("纯文本摘要", task.getValue().commentContentMd());
    }

    @Test
    void workspaceAccessRequestStaysOnItsDedicatedQueue() {
        var configs = mock(PlatformImChannelConfigService.class);
        var queue = mock(ImNotificationQueue.class);
        when(configs.selectedProvider()).thenReturn("DINGTALK");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class),
                mock(ImProviderRegistry.class), mock(SystemSettingService.class), queue);
        var notification = new NotificationDO();
        notification.setTenantId(1L);
        notification.setRecipientId(2L);
        notification.setType("WORKSPACE_ACCESS_REQUEST");

        assertTrue(channel.deliver(notification));
        verifyNoInteractions(queue);
    }
}
