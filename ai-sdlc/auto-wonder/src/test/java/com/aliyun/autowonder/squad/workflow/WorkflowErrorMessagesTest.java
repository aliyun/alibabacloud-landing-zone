package com.aliyun.autowonder.squad.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowErrorMessagesTest {

    @Test
    void rendersBilingualCopyForKnownCodes() {
        String message = WorkflowErrorMessages.message(WorkflowErrorMessages.DEADLINE_EXCEEDED);

        assertFalse(message.isBlank());
        assertTrue(message.contains("超时"));
    }

    @Test
    void executorFailureHasBilingualFallbackAndRetainsRawDiagnosticInBothLocales() {
        try {
            for (java.util.Locale locale : java.util.List.of(java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.US)) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                String fallback = WorkflowErrorMessages.message("EXECUTION_FAILED", null);
                assertFalse(fallback.isBlank());
                assertTrue(WorkflowErrorMessages.message("EXECUTION_FAILED", "qoder startup failed pid 38553")
                        .endsWith("qoder startup failed pid 38553"));
                if (locale.equals(java.util.Locale.US)) assertTrue(fallback.startsWith("Execution failed."));
                else assertTrue(fallback.startsWith("底层执行失败"));
            }
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void quotedCredentialsAreRemovedBeforeDiagnosticsReachThePage() {
        String detail = "qoder failed: {\"api_key\" : \"sk-test-secret-123\"} password = \"short-secret\" owner pid 38553";
        String message = WorkflowErrorMessages.message("EXECUTION_FAILED", detail);
        assertFalse(message.contains("sk-test-secret-123"));
        assertFalse(message.contains("short-secret"));
        assertTrue(message.contains("owner pid 38553"));
    }

    @Test
    void jsonAuthorizationAndSingleQuotedSecretsAreRedacted() {
        String message = WorkflowErrorMessages.message("EXECUTION_FAILED",
                "{\"Authorization\": \"Bearer abcdefghi\"} password = 'tiny' /opt/homebrew/bin/qodercli pid 38553");
        assertFalse(message.contains("abcdefghi"));
        assertFalse(message.contains("tiny"));
        assertTrue(message.contains("/opt/homebrew/bin/qodercli pid 38553"));
    }

    @Test
    void unknownAndNullCodesRenderNothing() {
        assertTrue(WorkflowErrorMessages.message("NOT_A_CODE").isEmpty());
        assertTrue(WorkflowErrorMessages.message(null).isEmpty());
    }

    @Test
    void onlyAnOversizedInputCannotBeRetriedFromThePage() {
        assertTrue(WorkflowErrorMessages.retryable(WorkflowErrorMessages.SUBMISSION_LIMIT));
        assertFalse(WorkflowErrorMessages.retryable(WorkflowErrorMessages.INPUT_TOO_LARGE));
        assertTrue(WorkflowErrorMessages.retryable(WorkflowErrorMessages.DEADLINE_EXCEEDED));
        assertTrue(WorkflowErrorMessages.retryable(WorkflowErrorMessages.NO_VALID_RESULT));
        assertTrue(WorkflowErrorMessages.retryable(null));
    }
}
