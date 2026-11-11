package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * One PRD-driven delivery and its per-requirement acceptance results.
 *
 * <p>The version identity travels with the results so a viewer can always tell which confirmed
 * version a delivery was built from, including after a newer version has been confirmed.
 */
@Getter
@Setter
public class PrdDeliveryAcceptanceVO {
    private Long dispatchId;
    private Long designVersionId;
    private Integer versionNo;
    private String designSha256;
    private Long targetRepoId;
    private String targetAppRoot;
    private String targetCommitSha;
    private List<RequirementAcceptanceVO> requirements;
    private int total;
    private int passed;
    private int failed;
    private int notVerified;
    private int notApplicable;
    /** False while anything is still unverified — the UI must not present this delivery as accepted. */
    private boolean complete;
}
