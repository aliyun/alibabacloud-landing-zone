package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.*;
import com.aliyun.autowonder.setting.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        var channel = new PlatformImNotifyChannel(configs, identities, registry, settings);
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
}
