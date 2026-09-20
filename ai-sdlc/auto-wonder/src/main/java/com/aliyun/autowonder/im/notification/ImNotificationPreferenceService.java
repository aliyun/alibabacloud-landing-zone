package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.notification.NotifyPrefDao;
import com.aliyun.autowonder.setting.SystemSettingService;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ImNotificationPreferenceService {
    private final NotifyPrefDao preferences;
    private final SystemSettingService settings;

    public ImNotificationPreferenceService(NotifyPrefDao preferences, SystemSettingService settings) {
        this.preferences = preferences;
        this.settings = settings;
    }

    public boolean isDisabled(long tenantId, long userId, String type, String provider) {
        String enabled = settings.getDecryptedValue("NOTIFY", provider.toLowerCase(Locale.ROOT) + "_enabled", tenantId);
        // Preserve existing assignment delivery until the project explicitly configures a switch.
        if (enabled != null && !Boolean.parseBoolean(enabled)) return true;
        var pref = preferences.findByUserAndType(tenantId, userId, type);
        if (pref == null) return false;
        Integer selected = "FEISHU".equals(provider) ? pref.getFeishu() : pref.getDingtalk();
        return !Integer.valueOf(1).equals(selected);
    }
}
