package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void scheduledRunSubscriberUsesJedisForTheInitialPatternSubscription() {
        RedisManager redisManager = mock(RedisManager.class);
        JedisPool pool = mock(JedisPool.class);
        Jedis mailboxJedis = blockingSubscriber();
        Jedis conversationJedis = blockingSubscriber();
        Jedis scheduledRunJedis = blockingPatternSubscriber();
        when(redisManager.getJedisPool()).thenReturn(pool);
        when(pool.getResource()).thenAnswer(invocation -> switch (Thread.currentThread().getName()) {
            case "ws-mailbox-subscriber" -> mailboxJedis;
            case "ws-conversation-subscriber" -> conversationJedis;
            case "ws-scheduled-run-subscriber" -> scheduledRunJedis;
            default -> throw new AssertionError("Unexpected subscriber thread");
        });

        WebSocketConfig config = new WebSocketConfig(redisManager,
                mock(NodeMailboxListener.class), mock(BrowserRealtimeSubscriberManager.class));
        try {
            config.startSubscriber();

            verify(scheduledRunJedis, timeout(1000))
                    .psubscribe(any(JedisPubSub.class), eq("scheduled-run:*"));
        } finally {
            config.stopSubscriber();
        }
    }

    private static Jedis blockingSubscriber() {
        Jedis jedis = mock(Jedis.class);
        doAnswer(invocation -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(jedis).subscribe(any(JedisPubSub.class), any(String.class));
        return jedis;
    }

    private static Jedis blockingPatternSubscriber() {
        Jedis jedis = mock(Jedis.class);
        doAnswer(invocation -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(jedis).psubscribe(any(JedisPubSub.class), eq("scheduled-run:*"));
        return jedis;
    }
}
