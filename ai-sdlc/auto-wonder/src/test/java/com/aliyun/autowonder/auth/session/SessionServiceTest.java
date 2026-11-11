package com.aliyun.autowonder.auth.session;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void extendRefreshSessionAtomicallyGatesOnExistingSessionAndTwoHourMarker() {
        RedisManager redis = mock(RedisManager.class);
        SessionService svc = new SessionService(redis);
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        SessionService.RenewalOutcome outcome = svc.extendRefreshSession("rt-1", 31536000);

        assertEquals(SessionService.RenewalOutcome.EXTENDED, outcome);
        verify(redis).eval(anyString(),
                eq(List.of("auth:refresh:rt-1", "auth:refresh:renew:rt-1")),
                eq(List.of("7200", "31536000")));
    }

    @Test
    void extendRefreshSessionReportsThrottledWithinTwoHours() {
        RedisManager redis = mock(RedisManager.class);
        SessionService svc = new SessionService(redis);
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(2L);

        assertEquals(SessionService.RenewalOutcome.THROTTLED,
                svc.extendRefreshSession("rt-1", 31536000));
    }

    @Test
    void extendRefreshSessionReportsMissingSessionForRevokedOrExpiredToken() {
        RedisManager redis = mock(RedisManager.class);
        SessionService svc = new SessionService(redis);
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        assertEquals(SessionService.RenewalOutcome.SESSION_MISSING,
                svc.extendRefreshSession("rt-1", 31536000));

        // 非数字返回（脚本异常等）也必须保守地视为会话不可用，绝不顺延。
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(null);
        assertEquals(SessionService.RenewalOutcome.SESSION_MISSING,
                svc.extendRefreshSession("rt-1", 31536000));
    }

    @Test
    void revokeRefreshDropsBothSessionAndRenewalMarker() {
        RedisManager redis = mock(RedisManager.class);
        SessionService svc = new SessionService(redis);

        svc.revokeRefresh("rt-1");

        verify(redis, times(1)).del("auth:refresh:rt-1");
        verify(redis, times(1)).del("auth:refresh:renew:rt-1");
    }
}
