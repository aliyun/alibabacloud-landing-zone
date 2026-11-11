package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** inApp 投递既要保留原有未读列表语义，也要触发实时事件；偏好关闭时 NotifyService 不会走进来。 */
class InAppChannelTest {

    @Test
    void pushesIdToListAndPublishesRealtimeEvent() {
        RedisManager redisManager = mock(RedisManager.class);
        NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
        InAppChannel channel = new InAppChannel(redisManager, publisher);

        NotificationDO row = new NotificationDO();
        row.setId(5L);
        row.setTenantId(10002L);
        row.setRecipientId(10018L);

        assertTrue(channel.deliver(row));

        verify(redisManager).lpush("notify:10018", "5");
        verify(publisher).publishAfterCommit(row);
    }
}
