package com.aliyun.autowonder.workitem.dto;

import com.aliyun.autowonder.aiusage.dto.StepUsageSummaryVO;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class DeliveryStepVO {
    private Long stepId;
    private String stepKey;
    private String name;
    private String status;
    private String planStatus;
    private Integer sourceAttempt;
    private String executorName;
    private String error;
    private List<SubStepVO> subSteps;
    private Long durationMs;
    private List<DispatchAttemptVO> attempts;
    private StepUsageSummaryVO usage;
}
