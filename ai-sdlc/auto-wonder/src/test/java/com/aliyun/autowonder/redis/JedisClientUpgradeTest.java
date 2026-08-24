package com.aliyun.autowonder.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisConnectionException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JedisClientUpgradeTest {

    @Test
    void jedisPoolCanBeBuiltWithProductionConstructor() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxIdle(2);
        poolConfig.setMinIdle(1);
        poolConfig.setMaxWaitMillis(500);

        try (JedisPool pool = new JedisPool(poolConfig, "127.0.0.1", 6379, 500, 500, null,
                Protocol.DEFAULT_DATABASE, null)) {
            assertNotNull(pool);
        }
    }

    @Test
    void borrowFailsFastWithConnectionExceptionWhenServerUnreachable() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(1);
        poolConfig.setMaxWaitMillis(1000);

        try (JedisPool pool = new JedisPool(poolConfig, "127.0.0.1", 1, 500, 500, null,
                Protocol.DEFAULT_DATABASE, null)) {
            assertThrows(JedisConnectionException.class, pool::getResource);
        }
    }
}
