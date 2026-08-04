package com.aliyun.autowonder.integration.aone;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedAoneRateLimiterTest {

    private static final String CLIENT_KEY = "auto-wonder";

    @Test
    void acquiresImmediatelyWhenTokenAvailableWithoutFallback() {
        AoneRateBucketDao dao = mock(AoneRateBucketDao.class);
        AoneThrottle fallback = mock(AoneThrottle.class);
        when(dao.tryAcquire(CLIENT_KEY)).thenReturn(1);
        DistributedAoneRateLimiter limiter =
                new DistributedAoneRateLimiter(dao, fallback, CLIENT_KEY, 1000L, 10L);

        limiter.acquire();

        verify(dao).tryAcquire(CLIENT_KEY);
        verify(fallback, never()).acquire();
    }

    @Test
    void fallsBackToLocalLimiterWhenBucketStaysEmpty() {
        AoneRateBucketDao dao = mock(AoneRateBucketDao.class);
        AoneThrottle fallback = mock(AoneThrottle.class);
        when(dao.tryAcquire(CLIENT_KEY)).thenReturn(0);
        // Tiny budget so the bounded poll exhausts quickly and never hangs.
        DistributedAoneRateLimiter limiter =
                new DistributedAoneRateLimiter(dao, fallback, CLIENT_KEY, 30L, 5L);

        long start = System.nanoTime();
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        verify(fallback, times(1)).acquire();
        assertTrue(elapsedMs < 2000, "acquire must be bounded, took " + elapsedMs + "ms");
    }

    @Test
    void failsOpenToFallbackWhenDaoThrows() {
        AoneRateBucketDao dao = mock(AoneRateBucketDao.class);
        AoneThrottle fallback = mock(AoneThrottle.class);
        when(dao.tryAcquire(eq(CLIENT_KEY)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        DistributedAoneRateLimiter limiter =
                new DistributedAoneRateLimiter(dao, fallback, CLIENT_KEY, 1000L, 10L);

        limiter.acquire();

        verify(fallback, times(1)).acquire();
    }
}
