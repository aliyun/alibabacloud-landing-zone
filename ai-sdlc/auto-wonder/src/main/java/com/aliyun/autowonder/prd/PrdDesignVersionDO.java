package com.aliyun.autowonder.prd;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * One PRD design version. {@code designJson} is the source of truth; the HTML and requirements
 * exports are derived from it, and {@code designSha256} is what confirmation binds to.
 */
@Getter
@Setter
public class PrdDesignVersionDO {
    private Long id;
    private Long workspaceId;
    private Long designId;
    private Integer versionNo;
    private String status;
    private Long sourceVersionId;
    private String designJson;
    private String designSha256;
    private String htmlOssRef;
    private String htmlSha256;
    private Long htmlSize;
    private String rendererVersion;
    private String summary;
    private String changeSummary;
    private Integer requirementCount;
    private Integer openQuestionCount;
    private Integer blockingQuestionCount;
    private Long aiSessionId;
    private Long confirmedBy;
    private Date confirmedAt;
    private String confirmedSha256;
    private Integer isDeleted;
    private Date deletedAt;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;

    /** A confirmed version is immutable: no further write may target it in place. */
    public boolean isConfirmed() {
        return PrdVersionStatus.CONFIRMED.equals(status);
    }
}
