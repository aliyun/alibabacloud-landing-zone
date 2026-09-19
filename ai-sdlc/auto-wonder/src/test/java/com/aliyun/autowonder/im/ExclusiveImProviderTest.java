package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExclusiveImProviderTest {
    @Test
    void selectingDisabledFeishuPersistsSelectionAndDisablesDingTalk() {
        var dao = mock(PlatformImChannelConfigDao.class);
        var service = new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        var request = new UpdateDingTalkChannelRequest();
        request.setEnabled(false);
        var result = service.update(1L, "FEISHU", request);
        assertTrue(result.isSelected());
        assertFalse(result.isEnabled());
        var order = inOrder(dao);
        order.verify(dao).lockSelection();
        order.verify(dao).upsert(any());
        order.verify(dao).disableOthers("FEISHU", 1L);
        order.verify(dao).selectProvider("FEISHU");
    }

    @Test
    void feishuRequiresAppCredentialsButNotDingTalkRobotCode() {
        var dao = mock(PlatformImChannelConfigDao.class);
        var keys = mock(SecretCrypto.class);
        when(keys.encrypt("secret")).thenReturn("encrypted");
        var service = new PlatformImChannelConfigService(dao, keys);
        var request = new UpdateDingTalkChannelRequest();
        request.setEnabled(true);
        request.setAppKey("cli_test");
        request.setAppSecret("secret");
        assertTrue(service.update(1L, "FEISHU", request).isReady());
        assertThrows(BizException.class, () -> service.update(1L, "DINGTALK", request));
    }

    @Test
    void selectedChannelIsReturnedForUserWithoutIdentityAndInactiveWritesAreRejected() {
        var dao = mock(PlatformImChannelConfigDao.class);
        when(dao.selectedProvider()).thenReturn("FEISHU");
        var config = new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        var identities = mock(UserImIdentityDao.class);
        var users = new UserImIdentityService(identities, config);
        var result = users.list(8L);
        assertEquals(1, result.size());
        assertEquals("FEISHU", result.get(0).getProvider());
        assertFalse(result.get(0).isConfigured());
        assertThrows(BizException.class, () -> users.update(8L, "DINGTALK", "staff"));
        assertThrows(BizException.class, () -> users.sendTest(8L, "DINGTALK"));
        verify(identities, never()).upsert(any());
        assertNull(config.findEnabled("DINGTALK"));
    }

    @Test
    void listIncludesSelectedChannelBeforeCredentialsAreConfigured() {
        var dao = mock(PlatformImChannelConfigDao.class);
        when(dao.selectedProvider()).thenReturn("FEISHU");
        var result = new PlatformImChannelConfigService(dao, mock(SecretCrypto.class)).list(1L);
        assertEquals(2, result.size());
        assertEquals("FEISHU", result.stream().filter(v -> v.isSelected()).findFirst().orElseThrow().getProvider());
    }

    @Test
    void queuedNotificationsRetainOriginalProviderAcrossPlatformSwitches() {
        assertEquals("FEISHU", task("COMMENT_MENTION:1:FEISHU:2").provider());
        assertEquals("DINGTALK", task("1:DINGTALK:2").provider());
        assertThrows(BizException.class, () -> ImProviderType.normalize("SLACK"));
    }

    private ImNotificationTask task(String key) {
        return new ImNotificationTask(key, 1, 1, 1, 2, "USER", 3, "name", null, "title");
    }
}
