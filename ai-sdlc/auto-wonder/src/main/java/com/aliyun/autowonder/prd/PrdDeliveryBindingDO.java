package com.aliyun.autowonder.prd;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * The frozen design a dispatch was built from, pinned at first packaging.
 *
 * <p>This row is what makes "retry and resume keep using the same frozen inputs" true: packaging
 * re-reads it instead of asking which version is confirmed now, so a later confirmation cannot
 * silently change what an in-flight delivery is implementing.
 */
@Getter
@Setter
public class PrdDeliveryBindingDO {
    private Long id;
    private Long workspaceId;
    private Long workitemId;
    private Long dispatchId;
    private Long designId;
    private Long designVersionId;
    private Integer versionNo;
    private String designSha256;
    private String htmlSha256;
    private Long targetRepoId;
    private Long targetProfileId;
    private String targetAppRoot;
    private String targetCommitSha;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}
