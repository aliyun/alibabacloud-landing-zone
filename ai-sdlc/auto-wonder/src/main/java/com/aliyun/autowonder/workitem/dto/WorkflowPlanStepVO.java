package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkflowPlanStepVO {
    private String stepKey;
    private String name;
    private String planStatus;
    private Integer sourceAttempt;
}
