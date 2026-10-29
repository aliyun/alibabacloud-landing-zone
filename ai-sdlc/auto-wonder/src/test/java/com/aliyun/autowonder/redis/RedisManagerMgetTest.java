package com.aliyun.autowonder.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisManagerMgetTest {

    private Jedis jedis;
    private RedisManager redis;

    @BeforeEach
    void setUp() {
        JedisPool pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        redis = new RedisManager(pool, false);
    }

    @Test
    void returnsEmptyWithoutTouchingRedisWhenNoKeyIsAsked() {
        assertTrue(redis.mget(null).isEmpty());
        assertTrue(redis.mget(List.of()).isEmpty());

        verifyNoInteractions(jedis);
    }

    @Test
    void returnsRawValuesInKeyOrderWithoutDecoding() {
        byte[] framed = AbstractRedisManager.serialize("plain");
        byte[] plain = bytes("raw");
        when(jedis.mget(any(byte[].class), any(byte[].class), any(byte[].class)))
                .thenReturn(Arrays.asList(framed, null, plain));

        List<byte[]> values = redis.mget(List.of("k1", "k2", "k3"));

        assertArrayEquals(framed, values.get(0),
                "framed values stay framed so the caller can decode each element on its own");
        assertNull(values.get(1), "an absent key keeps its position");
        assertArrayEquals(plain, values.get(2));
        ArgumentCaptor<byte[]> keys = ArgumentCaptor.forClass(byte[].class);
        verify(jedis).mget(keys.capture(), keys.capture(), keys.capture());
        List<byte[]> captured = keys.getAllValues();
        assertEquals(3, captured.size());
        assertEquals("k1", new String(captured.get(0), StandardCharsets.UTF_8));
        assertEquals("k2", new String(captured.get(1), StandardCharsets.UTF_8));
    }

    @Test
    void decodeStringAcceptsBothEncodings() {
        assertEquals("plain", AbstractRedisManager.decodeString("plain"));
        assertEquals("raw", AbstractRedisManager.decodeString("raw".getBytes(StandardCharsets.UTF_8)));
        assertEquals("value", AbstractRedisManager.decodeString(AbstractRedisManager.serialize("value")));
        assertNull(AbstractRedisManager.decodeString(null));
        assertNull(AbstractRedisManager.decodeString(42));
        assertNull(AbstractRedisManager.decodeString(new byte[0]));
        assertNull(AbstractRedisManager.decodeString(AbstractRedisManager.serialize(List.of("a"))));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
