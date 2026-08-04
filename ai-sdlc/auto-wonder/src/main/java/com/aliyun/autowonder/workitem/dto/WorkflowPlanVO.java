package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class WorkflowPlanVO {
    private Integer revision;
    private Long agentId;
    private String agentName;
    private String targetStepId;
    private String reason;
    private List<Long> sourceGuidanceIds;
    private List<WorkflowPlanStepVO> steps;
}
