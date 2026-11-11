package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

/** Binds a detected frontend application as the design's target project. */
@Getter
@Setter
public class SelectTargetRequest {
    private Long profileId;
    private Long repoId;
    private String appRoot;
    private String projectNotes;
}
