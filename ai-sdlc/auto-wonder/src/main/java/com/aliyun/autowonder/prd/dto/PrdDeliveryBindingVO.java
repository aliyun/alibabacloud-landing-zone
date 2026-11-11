package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** One PRD-driven delivery of a workitem: the frozen design version and its target project. */
@Getter
@Setter
public class PrdDeliveryBindingVO {
    private Long dispatchId;
    private Long workitemId;
    private Long designId;
    private Long designVersionId;
    private Integer versionNo;
    private String designSha256;
    private Long targetRepoId;
    private String targetAppRoot;
    private String targetCommitSha;
    private Date gmtCreate;
}
