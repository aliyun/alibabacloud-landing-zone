package com.aliyun.autowonder.integration.aone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fleet-wide throttle for Aone OpenAPI traffic. A per-JVM limiter cannot bound Aone's shared
 * 100 calls/min server-side quota once more than one instance runs, so this backs the throttle
 * with a MySQL token bucket ({@code aone_rate_bucket}) shared by every instance.
 *
 * <p>Each {@link #acquire()} attempts an atomic single-statement token consume. When the bucket
 * is momentarily empty it polls until a bounded budget is exhausted, then hands off to the local
 * {@link AoneRateLimiter} rather than blocking indefinitely. Any datastore failure (missing table,
 * missing row, DB down) also falls back to the local limiter, so this can only ever tighten the
 * rate, never turn a rate-limit into an outage.
 */
@Component
public class DistributedAoneRateLimiter implements AoneThrottle {

    private static final Logger log = LoggerFactory.getLogger(DistributedAoneRateLimiter.class);

    private static final String DEFAULT_CLIENT_KEY = "auto-wonder";
    private static final long DEFAULT_MAX_WAIT_MILLIS = 20_000L;
    private static final long DEFAULT_POLL_MILLIS = 100L;

    private final AoneRateBucketDao dao;
    private final AoneThrottle fallback;
    private final String clientKey;
    private final long maxWaitMillis;
    private final long pollMillis;

    @Autowired
    public DistributedAoneRateLimiter(AoneRateBucketDao dao) {
        this(dao, new AoneRateLimiter(), DEFAULT_CLIENT_KEY, DEFAULT_MAX_WAIT_MILLIS, DEFAULT_POLL_MILLIS);
    }

    DistributedAoneRateLimiter(AoneRateBucketDao dao, AoneThrottle fallback, String clientKey,
                               long maxWaitMillis, long pollMillis) {
        this.dao = dao;
        this.fallback = fallback;
        this.clientKey = clientKey;
        this.maxWaitMillis = maxWaitMillis;
        this.pollMillis = pollMillis;
    }

    @Override
    public void acquire() {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (true) {
            try {
                if (dao.tryAcquire(clientKey) == 1) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Aone rate bucket unavailable, falling back to local limiter: {}", e.toString());
                fallback.acquire();
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                fallback.acquire();
                return;
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fallback.acquire();
                return;
            }
        }
    }
}
