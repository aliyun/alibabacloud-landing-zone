package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

@Component
public class InAppChannel implements NotifyChannel {

    private final RedisManager redisManager;
    private final NotificationRealtimePublisher realtimePublisher;

    public InAppChannel(RedisManager redisManager, NotificationRealtimePublisher realtimePublisher) {
        this.redisManager = redisManager;
        this.realtimePublisher = realtimePublisher;
    }

    @Override
    public String name() {
        return "inApp";
    }

    @Override
    public boolean deliver(NotificationDO notification) {
        String channel = "notify:" + notification.getRecipientId();
        redisManager.lpush(channel, String.valueOf(notification.getId()));
        // 只有获得 inApp 投递资格的通知才实时推送，关闭该类型 inApp 偏好时不会弹提醒。
        realtimePublisher.publishAfterCommit(notification);
        return true;
    }
}
