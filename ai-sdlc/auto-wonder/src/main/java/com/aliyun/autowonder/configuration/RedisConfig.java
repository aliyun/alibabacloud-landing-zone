package com.aliyun.autowonder.configuration;

import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

@EnableCaching
@Configuration
public class RedisConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisConfig.class);

    @Bean("redisManager")
    public RedisManager redisManager(@Value("${spring.redis-meta.host:localhost}") String hostName,
                                     @Value("${spring.redis-meta.port:6379}") int port,
                                     @Value("${spring.redis-meta.password:123456}") String password,
                                     @Value("${spring.redis-meta.connectTimeoutMs:2000}") int connectTimeoutMs,
                                     @Value("${spring.redis-meta.socketTimeoutMs:5000}") int socketTimeoutMs,
                                     @Value("${spring.redis-meta.poolMaxTotal}") int poolMaxTotal,
                                     @Value("${application.env:daily}") String env) {

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(poolMaxTotal);
        poolConfig.setMaxIdle(poolMaxTotal);
        poolConfig.setMinIdle(poolMaxTotal / 2);
        poolConfig.setMaxWaitMillis(10000);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRunsMillis(30000);
        poolConfig.setMinEvictableIdleTimeMillis(60000);

        JedisPool jedisPool = new JedisPool(poolConfig, hostName, port, connectTimeoutMs, socketTimeoutMs,
                optionalPassword(password),
                Protocol.DEFAULT_DATABASE, null);
        boolean testEnterprise = !"daily".equals(env);
        return new RedisManager(jedisPool, testEnterprise);
    }

    static String optionalPassword(String password) {
        return password == null || password.isBlank() ? null : password;
    }
}
