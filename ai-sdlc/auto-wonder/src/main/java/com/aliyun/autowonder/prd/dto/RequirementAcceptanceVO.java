package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** One requirement's acceptance row as shown to the user in the delivery detail view. */
@Getter
@Setter
public class RequirementAcceptanceVO {
    private Long id;
    private Long dispatchId;
    private Long designVersionId;
    private String requirementKey;
    private String requirementTitle;
    private String priority;
    /** True when the confirmed design excluded this requirement from the round. */
    private boolean excluded;
    private String implementation;
    private String verdict;
    private String note;
    private String notApplicableReason;
    private List<Evidence> evidence;
    /** True until an explicit report arrives; drives the "not verified" styling. */
    private boolean verified;
    private Long reportedAt;

    @Getter
    @Setter
    public static class Evidence {
        private String kind;
        private String ref;
        private String title;
        /** Presigned download URL for ARTIFACT evidence; null otherwise. */
        private String url;
    }
}
