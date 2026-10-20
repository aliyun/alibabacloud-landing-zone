package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.user.UserSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Locale;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private final NotificationDao notificationDao;
    private final NotifyPrefDao prefDao;
    private final List<NotifyChannel> channels;
    private final UserSettingService userSettings;

    public NotifyService(NotificationDao notificationDao, NotifyPrefDao prefDao,
                         List<NotifyChannel> channels) {
        this(notificationDao, prefDao, channels, null);
    }

    @Autowired
    public NotifyService(NotificationDao notificationDao, NotifyPrefDao prefDao,
                         List<NotifyChannel> channels, UserSettingService userSettings) {
        this.userSettings = userSettings;
        this.notificationDao = notificationDao;
        this.prefDao = prefDao;
        this.channels = channels;
    }

    public void notify(NotifyEvent event) {
        if (event.getRecipientIds() == null || event.getRecipientIds().isEmpty()) {
            return;
        }
        for (Long recipientId : event.getRecipientIds()) {
            Locale locale = userSettings == null ? Locale.CHINA : userSettings.recipientLocale(recipientId);
            NotificationDO n = new NotificationDO();
            n.setRecipientLocale(locale.toLanguageTag());
            n.setTenantId(event.getTenantId());
            n.setRecipientId(recipientId);
            n.setType(event.getType());
            n.setTitle(SystemMessages.render(event.getTitleKey(), event.getTitleArgs(), event.getTitle(), locale));
            Object[] contentArgs = event.getContentKey() != null && event.getContentArgsResolver() != null
                    ? event.getContentArgsResolver().apply(locale) : event.getContentArgs();
            n.setContent(SystemMessages.render(event.getContentKey(), contentArgs, event.getContent(), locale));
            n.setLink(event.getLink());
            n.setRefType(event.getRefType());
            n.setRefId(event.getRefId());
            n.setStatus("UNREAD");
            notificationDao.insert(n);

            Map<String, String> channelResults = new HashMap<>();
            NotifyPrefDO pref = prefDao.findByUserAndType(event.getTenantId(), recipientId, event.getType());

            for (NotifyChannel channel : channels) {
                if (!shouldDeliver(channel.name(), pref)) {
                    continue;
                }
                try {
                    boolean ok = channel.deliver(n);
                    channelResults.put(channel.name(), ok ? "ok" : "failed");
                } catch (Exception e) {
                    log.warn("channel {} delivery failed for notification {}", channel.name(), n.getId(), e);
                    channelResults.put(channel.name(), "error:" + e.getMessage());
                }
            }
            notificationDao.updateChannels(n.getId(), event.getTenantId(), JSON.toJSONString(channelResults));
        }
    }

    public int unreadCount(long tenantId, long userId) {
        return notificationDao.countUnread(tenantId, userId);
    }

    public void markRead(long notificationId, long tenantId, long userId) {
        notificationDao.markRead(notificationId, tenantId, userId);
    }

    public void markAllRead(long tenantId, long userId) {
        notificationDao.markAllRead(tenantId, userId);
    }

    public void delete(long notificationId, long tenantId, long userId) {
        // 归属校验在 SQL 的 WHERE 上，0 行说明通知不存在或不属于当前用户，不能静默成功。
        if (notificationDao.delete(notificationId, tenantId, userId) == 0) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "error.notification_not_found", null, "通知不存在");
        }
    }

    private boolean shouldDeliver(String channelName, NotifyPrefDO pref) {
        if (pref == null) {
            return true;
        }
        if ("inApp".equals(channelName)) {
            return pref.getInApp() == null || pref.getInApp() == 1;
        }
        if ("feishu".equals(channelName)) {
            return pref.getFeishu() != null && pref.getFeishu() == 1;
        }
        if ("dingtalk".equals(channelName)) {
            return pref.getDingtalk() != null && pref.getDingtalk() == 1;
        }
        return true;
    }
}
