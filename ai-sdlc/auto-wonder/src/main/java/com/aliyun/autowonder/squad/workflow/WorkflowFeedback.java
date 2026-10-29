package com.aliyun.autowonder.squad.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Result of one independent validation pass, returned through the task tool as-is. Every feedback
 * carries the contract version and the generation context so an isolated Chief can repair without
 * guessing which attempt the answer belongs to.
 */
public record WorkflowFeedback(
        String schemaVersion,
        String status,
        String generationId,
        String attemptId,
        String submissionId,
        List<WorkflowValidationError> errors,
        String summary,
        boolean retryable,
        int remainingRepairAttempts,
        boolean errorsTruncated,
        String repairInstruction,
        JsonNode graph) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";

    public WorkflowFeedback {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (!ACCEPTED.equals(status)) {
            graph = null;
        }
    }

    /** Validator-local feedback before the service knows the generation context. */
    public WorkflowFeedback(String status, String submissionId,
            List<WorkflowValidationError> errors, String summary, JsonNode graph) {
        this(WorkflowValidator.SCHEMA_VERSION, status, null, null, submissionId, errors, summary,
                true, 0, false, null, graph);
    }

    public boolean accepted() {
        return ACCEPTED.equals(status);
    }

    public WorkflowFeedback truncated(boolean value) {
        return new WorkflowFeedback(schemaVersion, status, generationId, attemptId, submissionId,
                errors, summary, retryable, remainingRepairAttempts, value, repairInstruction, graph);
    }

    /**
     * Validator-local feedback is built before the submission context is known, so it carries a null
     * submissionId. The submission tool declares the id as a non-null string, therefore the service
     * always restores the submitted id before the feedback crosses the MCP boundary.
     */
    public WorkflowFeedback withSubmissionId(String value) {
        return new WorkflowFeedback(schemaVersion, status, generationId, attemptId, value, errors,
                summary, retryable, remainingRepairAttempts, errorsTruncated, repairInstruction, graph);
    }

    public WorkflowFeedback contextual(String generation, String attempt, int remainingSubmissions,
            boolean canRetry, boolean truncated, String instruction) {
        return new WorkflowFeedback(schemaVersion, status, generation, attempt, submissionId,
                errors, summary, canRetry, remainingSubmissions, truncated, instruction, graph);
    }
}
