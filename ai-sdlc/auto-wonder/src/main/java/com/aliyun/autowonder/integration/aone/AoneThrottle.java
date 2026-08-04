package com.aliyun.autowonder.integration.aone;

/**
 * Blocking throttle for Aone OpenAPI traffic. Implementations bound the emission rate below
 * Aone's shared server-side quota (100 calls/min for the {@code auto-wonder} clientKey).
 */
public interface AoneThrottle {

    /** Blocks until a call permit is available. */
    void acquire();
}
