package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void scheduledRunSubscriberUsesJedisForTheInitialPatternSubscription() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis scheduledRunJedis = blockingPatternSubscriber("scheduled-run:*");
        Jedis dispatchJedis = blockingPatternSubscriber("dispatch:*");
        JedisPool pool = subscriberPool(scheduledRunJedis, dispatchJedis);
        when(redisManager.getJedisPool()).thenReturn(pool);

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

    @Test
    void dispatchSubscriberSubscribesToDispatchPatternAndRelaysMessagesToTheBrowserChannel() {
        RedisManager redisManager = mock(RedisManager.class);
        Jedis scheduledRunJedis = blockingPatternSubscriber("scheduled-run:*");
        Jedis dispatchJedis = blockingPatternSubscriber("dispatch:*");
        BrowserRealtimeSubscriberManager subscriberManager = mock(BrowserRealtimeSubscriberManager.class);
        JedisPool pool = subscriberPool(scheduledRunJedis, dispatchJedis);
        when(redisManager.getJedisPool()).thenReturn(pool);

        WebSocketConfig config = new WebSocketConfig(redisManager,
                mock(NodeMailboxListener.class), subscriberManager);
        try {
            config.startSubscriber();

            ArgumentCaptor<JedisPubSub> pubSub = ArgumentCaptor.forClass(JedisPubSub.class);
            verify(dispatchJedis, timeout(1000)).psubscribe(pubSub.capture(), eq("dispatch:*"));

            pubSub.getValue().onPMessage("dispatch:*", "dispatch:10", "{\"type\":\"x\"}");

            verify(subscriberManager, times(1)).deliverToChannel("dispatch:10", "{\"type\":\"x\"}");
        } finally {
            config.stopSubscriber();
        }
    }

    private static JedisPool subscriberPool(Jedis scheduledRunJedis, Jedis dispatchJedis) {
        Jedis mailboxJedis = blockingSubscriber();
        Jedis conversationJedis = blockingSubscriber();
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenAnswer(invocation -> switch (Thread.currentThread().getName()) {
            case "ws-mailbox-subscriber" -> mailboxJedis;
            case "ws-conversation-subscriber" -> conversationJedis;
            case "ws-scheduled-run-subscriber" -> scheduledRunJedis;
            case "ws-dispatch-subscriber" -> dispatchJedis;
            default -> throw new AssertionError("Unexpected subscriber thread");
        });
        return pool;
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

    private static Jedis blockingPatternSubscriber(String pattern) {
        Jedis jedis = mock(Jedis.class);
        doAnswer(invocation -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(jedis).psubscribe(any(JedisPubSub.class), eq(pattern));
        return jedis;
    }
}
