package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.i18n.SystemMessages;

import java.util.Map;

/** Maps persisted workflow error codes to the bilingual system messages shown by the page. */
public final class WorkflowErrorMessages {

    public static final String CHIEF_UNAVAILABLE = "CHIEF_UNAVAILABLE";
    public static final String DISPATCH_FAILED = "DISPATCH_FAILED";
    public static final String EXECUTION_FAILED = "EXECUTION_FAILED";
    public static final String EXECUTION_CANCELED = "EXECUTION_CANCELED";
    public static final String NO_VALID_RESULT = "NO_VALID_RESULT";
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    public static final String SUBMISSION_LIMIT = "SUBMISSION_LIMIT";
    public static final String INPUT_TOO_LARGE = "INPUT_TOO_LARGE";
    public static final String EXECUTOR_UNAVAILABLE = "EXECUTOR_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry(CHIEF_UNAVAILABLE, "error.squad_workflow_chief_unavailable"),
            Map.entry(DISPATCH_FAILED, "squad.workflow.failed"),
            Map.entry(EXECUTION_FAILED, "squad.workflow.execution_failed"),
            Map.entry(EXECUTION_CANCELED, "squad.workflow.execution_canceled"),
            Map.entry(NO_VALID_RESULT, "squad.workflow.no_result"),
            Map.entry(DEADLINE_EXCEEDED, "error.squad_workflow_deadline_exceeded"),
            Map.entry(SUBMISSION_LIMIT, "squad.workflow.repair_exhausted"),
            Map.entry(INPUT_TOO_LARGE, "error.squad_workflow_input_too_large"),
            Map.entry(EXECUTOR_UNAVAILABLE, "error.squad_workflow_chief_unavailable"),
            Map.entry(INTERNAL_ERROR, "squad.workflow.failed"));

    // The shared action sanitizer handles unquoted key=value pairs. Executor errors
    // can also embed JSON or quoted shell values; remove those before formatting.
    private static final java.util.regex.Pattern QUOTED_SECRET = java.util.regex.Pattern.compile(
            "(?i)([\"']?[a-z0-9_.-]*(?:token|secret|password|passwd|pwd|apikey|api_key|api-key|accesskey|access_key|credential|signature|privatekey|private_key|sessionkey|authcode|authorization)[a-z0-9_.-]*[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*(?:\"|$)|'(?:\\\\.|[^'\\\\])*(?:'|$))");

    private WorkflowErrorMessages() {
    }

    public static String message(String code) {
        if (code == null) {
            return "";
        }
        String key = KEYS.get(code);
        return key == null ? "" : SystemMessages.render(key, null, null);
    }

    /** Render provider diagnostics as plain text while keeping the persisted original untouched. */
    public static String message(String code, String detail) {
        String message = message(code);
        if (!EXECUTION_FAILED.equals(code) && !DISPATCH_FAILED.equals(code)
                && !EXECUTION_CANCELED.equals(code)) {
            return message;
        }
        String quotedRedacted = detail == null ? null
                : QUOTED_SECRET.matcher(detail).replaceAll("$1\"[REDACTED]\"");
        String diagnostic = com.aliyun.autowonder.util.RuntimeActionSanitizer.sanitize(quotedRedacted, 1000);
        return diagnostic == null ? message : message + "\n" + diagnostic;
    }

    /**
     * Whether the page may start a new generation attempt for the same input. This is a view-level
     * signal only: eligibility to repair the current attempt is computed per feedback.
     */
    public static boolean retryable(String code) {
        return !INPUT_TOO_LARGE.equals(code);
    }
}
