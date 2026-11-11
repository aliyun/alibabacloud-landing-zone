package com.aliyun.autowonder.frontendproject;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** One durable probe run against a repo. Status constants live in {@link ProbeStatus}. */
@Getter
@Setter
public class FrontendProjectProbeDO {
    private Long id;
    private Long workspaceId;
    private Long repoId;
    private String status;
    private String detectorVersion;
    private String branch;
    private String commitSha;
    private Integer candidateCount;
    private String error;
    private String nodeId;
    private Date startedAt;
    private Date finishedAt;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}
