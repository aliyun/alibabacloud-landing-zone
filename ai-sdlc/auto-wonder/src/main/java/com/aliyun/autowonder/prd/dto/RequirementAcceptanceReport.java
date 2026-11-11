package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Executor's report for a single frozen requirement. The requirement key must match a key of the
 * pinned design version; anything else is rejected rather than stored, so a report can never attach
 * to a requirement the user did not confirm.
 */
@Getter
@Setter
public class RequirementAcceptanceReport {
    private String requirementKey;
    /** IMPLEMENTED/PARTIAL/NOT_IMPLEMENTED. */
    private String implementation;
    /** PASSED/FAILED/NOT_VERIFIED/NOT_APPLICABLE. */
    private String verdict;
    private String note;
    /** Required when verdict is NOT_APPLICABLE. */
    private String notApplicableReason;
    private List<EvidenceRef> evidence;

    /** One evidence locator. The kind set is closed and excludes the design prototype. */
    @Getter
    @Setter
    public static class EvidenceRef {
        private String kind;
        /** ARTIFACT: artifact id of this dispatch. Otherwise a path, URL or test identifier. */
        private String ref;
        private String title;
    }
}
