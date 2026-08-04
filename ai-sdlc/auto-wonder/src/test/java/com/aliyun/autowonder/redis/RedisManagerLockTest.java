package com.aliyun.autowonder.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedisManagerLockTest {

    @Test
    void releasesLockOnlyWhenOwnerTokenMatches() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), eq(List.of("dispatch:lock:1")), eq(List.of("owner-1"))))
                .thenReturn(1L);
        RedisManager redis = new RedisManager(pool, false);

        assertTrue(redis.releaseLock("dispatch:lock:1", "owner-1"));
        verify(jedis, never()).del("dispatch:lock:1");
    }

    @Test
    void reportsFalseWhenLockIsOwnedByAnotherWorker() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        RedisManager redis = new RedisManager(pool, false);

        assertFalse(redis.releaseLock("dispatch:lock:1", "stale-owner"));
    }
}
