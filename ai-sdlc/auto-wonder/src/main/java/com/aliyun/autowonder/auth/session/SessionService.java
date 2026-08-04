package com.aliyun.autowonder.auth.session;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final RedisManager redisManager;

    public SessionService(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void storeRefresh(String refreshToken, long userId, int ttlSeconds) {
        redisManager.set(REFRESH_PREFIX + refreshToken, userId, ttlSeconds);
    }

    public Long getUserIdByRefresh(String refreshToken) {
        return redisManager.get(REFRESH_PREFIX + refreshToken);
    }

    public void revokeRefresh(String refreshToken) {
        redisManager.del(REFRESH_PREFIX + refreshToken);
    }

    public void blacklistJti(String jti, int ttlSeconds) {
        redisManager.set(BLACKLIST_PREFIX + jti, "1", ttlSeconds);
    }

    public boolean isBlacklisted(String jti) {
        return redisManager.exists(BLACKLIST_PREFIX + jti);
    }
}
