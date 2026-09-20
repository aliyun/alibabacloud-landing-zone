package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.notification.NotifyPrefDO;
import com.aliyun.autowonder.notification.NotifyPrefDao;
import com.aliyun.autowonder.setting.SystemSettingService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImNotificationPreferenceServiceTest {
    @Test void preservesLegacyDeliveryButHonorsSelectedProviderPreferencesAndProjectSwitch() {
        var prefs = mock(NotifyPrefDao.class);
        var settings = mock(SystemSettingService.class);
        var service = new ImNotificationPreferenceService(prefs, settings);
        assertFalse(service.isDisabled(1L, 2L, "WORKITEM_ASSIGNED", "DINGTALK"));
        var pref = new NotifyPrefDO(); pref.setDingtalk(1); pref.setFeishu(0);
        when(prefs.findByUserAndType(1L, 2L, "WORKITEM_ASSIGNED")).thenReturn(pref);
        assertTrue(service.isDisabled(1L, 2L, "WORKITEM_ASSIGNED", "FEISHU"));
        assertFalse(service.isDisabled(1L, 2L, "WORKITEM_ASSIGNED", "DINGTALK"));
        pref.setFeishu(1);
        assertFalse(service.isDisabled(1L, 2L, "WORKITEM_ASSIGNED", "FEISHU"));
        when(settings.getDecryptedValue("NOTIFY", "feishu_enabled", 1L)).thenReturn("false");
        assertTrue(service.isDisabled(1L, 2L, "WORKITEM_ASSIGNED", "FEISHU"));
    }
}
