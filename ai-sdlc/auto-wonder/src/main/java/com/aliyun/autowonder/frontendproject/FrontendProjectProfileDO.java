package com.aliyun.autowonder.frontendproject;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * Immutable profile snapshot of one frontend application, keyed by
 * repo + commit + app root + detector version.
 */
@Getter
@Setter
public class FrontendProjectProfileDO {
    private Long id;
    private Long workspaceId;
    private Long repoId;
    private Long probeId;
    private String branch;
    private String commitSha;
    private String appRoot;
    private String appRootHash;
    private String packageName;
    private String detectorVersion;
    private String profileJson;
    private String evidenceJson;
    private Boolean looksLikeLibrary;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}
