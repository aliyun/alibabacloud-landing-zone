package com.aliyun.autowonder.dispatch.dto;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class DispatchVO {
    private Long id;
    private String sourceType;
    private Long workitemId;
    private Long sdlcStepId;
    private Long agentId;
    private Long agentVersionId;
    private Long executorId;
    private String status;
    private Integer attempt;
    private String resultSummary;
    private String error;
    private String packageOssRef;
    private Date gmtCreate;
    private Date gmtModified;

    // enriched display fields
    private String workitemTitle;
    private String agentName;
    private Integer agentVersionNo;
    private String executorName;

    // detail-only; null in list responses
    private List<ArtifactVO> artifacts;
}
