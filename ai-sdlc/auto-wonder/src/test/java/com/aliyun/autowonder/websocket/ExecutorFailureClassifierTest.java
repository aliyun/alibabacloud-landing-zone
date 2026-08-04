package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorFailureClassifierTest {

    @Test
    void classifiesStrictLegacyProviderFailures() {
        assertEquals("agent_error.provider_quota_limit",
                ExecutorFailureClassifier.classify("You've hit your usage limit. Purchase more credits."));
        assertEquals("agent_error.provider_auth_or_access",
                ExecutorFailureClassifier.classify("Not logged in; please login again"));
        assertEquals("agent_error.provider_server_error",
                ExecutorFailureClassifier.classify("HTTP 503 Service Unavailable from provider API"));
    }

    @Test
    void doesNotClassifyBusinessOrTestFailures() {
        assertNull(ExecutorFailureClassifier.classify("tests failed"));
        assertNull(ExecutorFailureClassifier.classify("permission denied writing local file"));
        assertNull(ExecutorFailureClassifier.classify(null));
    }

    @Test
    void classifiesMissingProviderSessionsAsRuntimeRecovery() {
        assertEquals("runtime_recovery", ExecutorFailureClassifier.classify(
                "claude stderr: No conversation found with session ID: stale"));
        assertEquals("runtime_recovery", ExecutorFailureClassifier.classify(
                "qoder session/fork failed: Source session stale does not exist"));
        assertEquals("runtime_recovery", ExecutorFailureClassifier.classify(
                "codex thread/fork failed: no rollout found for thread id stale"));
    }
}
