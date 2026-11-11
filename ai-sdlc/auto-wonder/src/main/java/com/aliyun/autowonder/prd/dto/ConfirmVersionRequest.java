package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Confirms a specific version. {@code expectedDigest} is the content digest the user actually
 * reviewed; when it no longer matches the stored design the confirmation is refused rather than
 * silently confirming content that changed underneath the reviewer.
 */
@Getter
@Setter
public class ConfirmVersionRequest {
    private Long versionId;
    private String expectedDigest;
    /** LEGACY keeps the workitem's existing delivery path; PRD_DRIVEN enforces the confirmed version. */
    private String deliveryMode;
    /** Explicit acknowledgement of unresolved questions for the reviewed version only. */
    private boolean ignoreOpenQuestions;
}
