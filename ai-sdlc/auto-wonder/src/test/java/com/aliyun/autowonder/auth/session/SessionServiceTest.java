package com.aliyun.autowonder.auth.session;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {
    @Test
    void storesAndReadsRefresh() {
        RedisManager redis = mock(RedisManager.class);
        SessionService svc = new SessionService(redis);

        svc.storeRefresh("rt-1", 42L, 100);
        verify(redis).set("auth:refresh:rt-1", 42L, 100);

        when(redis.<Long>get("auth:refresh:rt-1")).thenReturn(42L);
        assertEquals(42L, svc.getUserIdByRefresh("rt-1"));
    }

    @Test
    void blacklistsJti() {
        RedisManager redis = mock(RedisManager.class);
        SessionService svc = new SessionService(redis);

        svc.blacklistJti("j-1", 200);
        verify(redis).set("jwt:blacklist:j-1", "1", 200);

        when(redis.exists("jwt:blacklist:j-1")).thenReturn(true);
        assertTrue(svc.isBlacklisted("j-1"));
    }
}
