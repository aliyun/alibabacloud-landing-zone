package com.aliyun.autowonder.frontendproject.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** A frontend project probe run as seen by the UI. */
@Getter
@Setter
public class FrontendProjectProbeVO {
    private Long id;
    private Long repoId;
    private String repoName;
    private String status;
    private String detectorVersion;
    private String branch;
    private String commitSha;
    private Integer candidateCount;
    private String error;
    private Date startedAt;
    private Date finishedAt;
    private Date gmtCreate;
    private List<FrontendProjectProfileVO> profiles = new ArrayList<>();
}
