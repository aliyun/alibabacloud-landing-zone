package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.ImSendCommand;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.setting.SystemSettingService;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Project notification preferences reuse the single platform bot and personal IM identity. */
@Component
public class PlatformImNotifyChannel implements NotifyChannel {
    private final PlatformImChannelConfigService configs;
    private final UserImIdentityService identities;
    private final ImProviderRegistry providers;
    private final SystemSettingService settings;

    public PlatformImNotifyChannel(PlatformImChannelConfigService configs, UserImIdentityService identities,
                                   ImProviderRegistry providers, SystemSettingService settings) {
        this.configs = configs;
        this.identities = identities;
        this.providers = providers;
        this.settings = settings;
    }

    @Override
    public String name() { return configs.selectedProvider().toLowerCase(Locale.ROOT); }

    @Override
    public boolean deliver(NotificationDO notification) {
        // These events have their own reliable IM queue; their in-app listeners must not send twice.
        if (java.util.Set.of("WORKITEM_ASSIGNED", "COMMENT_MENTION", "WORKSPACE_ACCESS_REQUEST", "WORKSPACE_ACCESS_REVIEWED")
                .contains(notification.getType() == null ? "" : notification.getType())) return true;
        String provider = configs.selectedProvider();
        if (!configs.isReady(provider) || !Boolean.parseBoolean(settings.getDecryptedValue(
                "NOTIFY", provider.toLowerCase(Locale.ROOT) + "_enabled", notification.getTenantId()))) return false;
        var identity = identities.find(notification.getRecipientId(), provider);
        if (identity == null || identity.getExternalUserId() == null || identity.getExternalUserId().isBlank()) return false;
        providers.require(provider).send(new ImSendCommand(provider, identity.getExternalUserId(),
                notification.getTitle(), notification.getContent() == null ? notification.getTitle() : notification.getContent()));
        return true;
    }
}
