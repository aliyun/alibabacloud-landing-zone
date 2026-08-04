package com.aliyun.autowonder.websocket;

import java.util.Locale;

/**
 * Compatibility classifier for runtimes that predate structured failure metadata.
 * It intentionally recognizes only unmistakable provider/executor failures so a
 * normal task, test, or repository error never causes infrastructure failover.
 */
final class ExecutorFailureClassifier {

    private ExecutorFailureClassifier() {
    }

    static String classify(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String normalized = error.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "no conversation found with session id",
                "no rollout found for thread id",
                "invalid session identifier",
                "session not found")
                || (normalized.contains("source session") && normalized.contains("does not exist"))) {
            return "runtime_recovery";
        }
        if (containsAny(normalized,
                "you've hit your usage limit", "you have hit your usage limit",
                "usage limit", "payment required", "insufficient_balance",
                "insufficient balance", "balance is too low", "purchase more credits",
                "quota exceeded")) {
            return "agent_error.provider_quota_limit";
        }
        if (containsAny(normalized,
                "not logged in", "login required", "please login again",
                "invalid api key", "invalid_api_key", "authentication failed",
                "access token has expired", "access token expired", "access token revoked")) {
            return "agent_error.provider_auth_or_access";
        }
        if (containsAny(normalized, "http 429", "status 429", "too many requests",
                "rate limit exceeded", "rate_limit_exceeded")) {
            return "agent_error.provider_capacity_or_rate_limit";
        }
        if (containsAny(normalized, "http 500", "http 502", "http 503", "http 504",
                "status 500", "status 502", "status 503", "status 504")
                && containsAny(normalized, "provider", " api", "service unavailable", "bad gateway")) {
            return "agent_error.provider_server_error";
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
