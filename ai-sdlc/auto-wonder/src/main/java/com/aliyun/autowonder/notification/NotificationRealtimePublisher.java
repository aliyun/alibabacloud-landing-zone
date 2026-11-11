package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import redis.clients.jedis.Jedis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把「新通知已持久化」这件事推给该通知的接收者，正文不含在事件里，前端凭 ID 走鉴权详情接口取内容。
 */
@Component
public class NotificationRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationRealtimePublisher.class);

    public static final String TYPE_NOTIFICATION_CREATED = "NOTIFICATION_CREATED";
    static final String CHANNEL_PREFIX = "notification:";

    private final RedisManager redisManager;

    public NotificationRealtimePublisher(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public static String channelFor(long workspaceId, long userId) {
        return CHANNEL_PREFIX + workspaceId + ":" + userId;
    }

    /**
     * 发布绑定「确实完成的提交」：有事务同步时只在 afterCompletion(STATUS_COMMITTED) 发布，
     * 回滚不发布；没有同步说明写入已由 {@link NotificationPersister} 独立提交，直接发布。
     *
     * <p>不再做可见性探测与等待：AFTER_COMMIT 回调内那条连接要等监听器返回才提交，
     * 任何窗口内探测都必然失败（E2E F6），改为先独立提交再发布。
     */
    public void publishAfterCommit(NotificationDO notification) {
        if (!isPublishable(notification)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        publish(notification);
                    }
                }
            });
            return;
        }
        publish(notification);
    }

    private boolean isPublishable(NotificationDO notification) {
        return notification != null
                && notification.getId() != null && notification.getId() > 0
                && notification.getTenantId() != null && notification.getTenantId() > 0
                && notification.getRecipientId() != null && notification.getRecipientId() > 0;
    }

    void publish(NotificationDO notification) {
        String channel = channelFor(notification.getTenantId(), notification.getRecipientId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", notification.getTenantId());
        payload.put("recipientId", notification.getRecipientId());
        payload.put("notificationId", notification.getId());

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("channel", channel);
        frame.put("type", TYPE_NOTIFICATION_CREATED);
        frame.put("payload", payload);
        frame.put("timestamp", System.currentTimeMillis());

        try (Jedis jedis = redisManager.getJedisPool().getResource()) {
            jedis.publish(channel, JSON.toJSONString(frame));
        } catch (Exception e) {
            // 可恢复的投递异常不能把已成功的业务写入变成失败。
            log.warn("notification realtime publish failed channel={}: {}", channel, e.getMessage());
        }
    }
}
