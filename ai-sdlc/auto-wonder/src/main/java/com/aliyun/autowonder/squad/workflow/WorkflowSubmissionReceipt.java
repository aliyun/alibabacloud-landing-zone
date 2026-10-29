package com.aliyun.autowonder.squad.workflow;

import java.util.List;

/**
 * One accounted submission for the current attempt. The payload digest makes a transport retry with
 * the same submissionId replayable without spending repair budget; a different digest is a conflict.
 */
public record WorkflowSubmissionReceipt(
        String submissionId,
        String digest,
        String status,
        String submittedAt,
        List<WorkflowValidationError> errors,
        String summary,
        boolean errorsTruncated) {

    public WorkflowSubmissionReceipt(String submissionId, String digest, String status,
            String submittedAt, List<WorkflowValidationError> errors, String summary) {
        this(submissionId, digest, status, submittedAt, errors, summary, false);
    }

    public boolean matches(String otherSubmissionId, String otherDigest) {
        return submissionId != null && submissionId.equals(otherSubmissionId)
                && digest != null && digest.equals(otherDigest);
    }
}
