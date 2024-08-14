package org.example.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * 示例中使用简单的本地缓存，请更换为您真实使用的持久化缓存
     * 缓存项的缓存时间一定要小于STS Token有效期，避免缓存时间过长而STS Token过期导致程序错误
     * 示例中，缓存时间为STS Token的有效期（60min）-5min
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(3300, TimeUnit.SECONDS)
        );
        return cacheManager;
    }
}
