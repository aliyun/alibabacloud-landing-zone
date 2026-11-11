package com.aliyun.autowonder.prd;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * One requirement's acceptance result within one delivery.
 *
 * <p>A row is seeded for every requirement key of the frozen design version before the executor
 * starts, with {@code verdict = NOT_VERIFIED} and {@code implementation = NOT_IMPLEMENTED}. Seeding
 * is what makes "every requirement has a result" and "not verified is never shown as passed" true by
 * construction: there is no state in which a requirement is simply absent, and no path that moves a
 * row off NOT_VERIFIED except an explicit report.
 */
@Getter
@Setter
public class PrdRequirementAcceptanceDO {
    private Long id;
    private Long workspaceId;
    private Long workitemId;
    private Long dispatchId;
    private Long designVersionId;
    private String requirementKey;
    private String implementation;
    private String verdict;
    private String note;
    private String notApplicableReason;
    private String evidenceJson;
    private Date reportedAt;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}
