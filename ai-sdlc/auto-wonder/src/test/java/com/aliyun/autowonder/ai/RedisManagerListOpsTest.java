package com.aliyun.autowonder.ai;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisManagerListOpsTest {

    private JedisPool jedisPool;
    private Jedis jedis;
    private RedisManager redisManager;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        redisManager = new RedisManager(jedisPool, false);
    }

    @Test
    void lpushCallsJedis() {
        redisManager.lpush("q", "val");
        verify(jedis).lpush("q", "val");
    }

    @Test
    void brpopReturnsValue() {
        when(jedis.brpop(5, "q")).thenReturn(List.of("q", "payload"));
        String result = redisManager.brpop("q", 5);
        assertEquals("payload", result);
    }

    @Test
    void brpopReturnsNullOnTimeout() {
        when(jedis.brpop(5, "q")).thenReturn(null);
        assertNull(redisManager.brpop("q", 5));
    }

    @Test
    void llenReturnsCount() {
        when(jedis.llen("q")).thenReturn(3L);
        assertEquals(3L, redisManager.llen("q"));
    }
}
