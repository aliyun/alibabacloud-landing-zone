package com.aliyun.autowonder.integration.aone;

public class AoneOpenApiException extends RuntimeException {

    /**
     * Terminal errors will never succeed on retry (business rejections, 4xx, malformed
     * responses), so the outbox dispatcher dead-letters them instead of re-attempting and
     * burning the shared Aone quota. Transient errors (rate limit, 5xx, IO) default to
     * false so they stay retryable.
     */
    private final boolean terminal;

    public AoneOpenApiException(String message) {
        this(message, false);
    }

    public AoneOpenApiException(String message, boolean terminal) {
        super(message);
        this.terminal = terminal;
    }

    public AoneOpenApiException(String message, Throwable cause) {
        super(message, cause);
        this.terminal = false;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
