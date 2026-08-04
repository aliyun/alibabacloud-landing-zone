package com.aliyun.autowonder.ai.engine;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.BrowserRealtimePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiStreamPublisherTest {

    private RedisManager redisManager;
    private BrowserRealtimePublisher browserRealtimePublisher;
    private AiStreamPublisher publisher;

    @BeforeEach
    void setUp() {
        redisManager = mock(RedisManager.class);
        browserRealtimePublisher = mock(BrowserRealtimePublisher.class);
        publisher = new AiStreamPublisher(redisManager, browserRealtimePublisher);
    }

    @Test
    void publishDeltaAlsoBroadcastsBrowserRealtimeFrame() {
        publisher.publishDelta(100L, 200L, "hello");

        verify(redisManager).publish(eq("ai:stream:100"), anyString());
        verify(browserRealtimePublisher).publish(
                eq(200L),
                eq("ai:session:100"),
                eq("AI_STREAM"),
                org.mockito.ArgumentMatchers.argThat(payload -> {
                    Map<?, ?> map = (Map<?, ?>) payload;
                    assertEquals("delta", map.get("type"));
                    assertEquals(100L, map.get("sessionId"));
                    assertEquals("hello", map.get("text"));
                    return true;
                })
        );
    }
}
