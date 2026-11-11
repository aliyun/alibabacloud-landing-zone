package com.aliyun.autowonder.prd;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** Design aggregate root for one workitem: target project context plus the confirmed version. */
@Getter
@Setter
public class PrdDesignDO {
    private Long id;
    private Long workspaceId;
    private Long workitemId;
    private Long targetRepoId;
    private Long targetProfileId;
    private String targetAppRoot;
    private String targetCommitSha;
    private String targetConfigJson;
    private Long confirmedVersionId;
    private String deliveryMode;
    private Integer latestVersionNo;
    private String contentLanguage;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;

    /** Whether a version has been confirmed and can drive delivery. */
    public boolean hasConfirmedVersion() {
        return confirmedVersionId != null;
    }
}
