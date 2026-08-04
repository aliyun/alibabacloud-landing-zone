package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

@Component
public class InAppChannel implements NotifyChannel {

    private final RedisManager redisManager;

    public InAppChannel(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    @Override
    public String name() {
        return "inApp";
    }

    @Override
    public boolean deliver(NotificationDO notification) {
        String channel = "notify:" + notification.getRecipientId();
        redisManager.lpush(channel, String.valueOf(notification.getId()));
        return true;
    }
}
