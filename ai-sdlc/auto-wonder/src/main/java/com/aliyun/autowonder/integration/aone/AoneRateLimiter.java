package com.aliyun.autowonder.integration.aone;

import com.google.common.util.concurrent.RateLimiter;

/**
 * Shared client-side throttle for Aone OpenAPI traffic. Aone enforces a
 * server-side quota of 100 calls/min for the {@code auto-wonder} client key,
 * shared across every IssueTopService endpoint. Keeping our own emission rate
 * below that ceiling prevents the whole quota from being burned by a single
 * caller (poller, dispatcher, or manual sync) and starving the others.
 */
public class AoneRateLimiter implements AoneThrottle {

    /** 90 calls/min, safely under Aone's 100/min server-side quota. */
    public static final double DEFAULT_PERMITS_PER_SECOND = 1.5;

    private final RateLimiter rateLimiter;

    public AoneRateLimiter() {
        this(DEFAULT_PERMITS_PER_SECOND);
    }

    public AoneRateLimiter(double permitsPerSecond) {
        this.rateLimiter = RateLimiter.create(permitsPerSecond);
    }

    public void acquire() {
        rateLimiter.acquire();
    }
}
