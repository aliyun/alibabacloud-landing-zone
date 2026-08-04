package com.aliyun.autowonder.notification;

import com.alibaba.fastjson.JSON;
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

    public NotifyService(NotificationDao notificationDao, NotifyPrefDao prefDao,
                         List<NotifyChannel> channels) {
        this.notificationDao = notificationDao;
        this.prefDao = prefDao;
        this.channels = channels;
    }

    public void notify(NotifyEvent event) {
        if (event.getRecipientIds() == null || event.getRecipientIds().isEmpty()) {
            return;
        }
        for (Long recipientId : event.getRecipientIds()) {
            NotificationDO n = new NotificationDO();
            n.setTenantId(event.getTenantId());
            n.setRecipientId(recipientId);
            n.setType(event.getType());
            n.setTitle(event.getTitle());
            n.setContent(event.getContent());
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

    private boolean shouldDeliver(String channelName, NotifyPrefDO pref) {
        if (pref == null) {
            return true;
        }
        if ("inApp".equals(channelName)) {
            return pref.getInApp() == null || pref.getInApp() == 1;
        }
        if ("dingtalk".equals(channelName)) {
            return pref.getDingtalk() != null && pref.getDingtalk() == 1;
        }
        return true;
    }
}
