package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * One design version. {@code designJson} is only populated for single-version reads — the history
 * list stays metadata-only so loading versions never pulls every design payload.
 */
@Getter
@Setter
public class PrdDesignVersionVO {
    private Long id;
    private Integer version;
    private Long designId;
    private Integer versionNo;
    private String status;
    private Long sourceVersionId;
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
    private Date gmtCreate;
    private String designJson;
}
