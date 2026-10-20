package com.aliyun.autowonder.workitem.dto;

import com.aliyun.autowonder.aiusage.dto.StepUsageSummaryVO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AgentDeliveryProgressVO {
    private Long agentId;
    private String agentName;
    private String status;
    private Long currentDispatchId;
    private String executionStatus;
    private String waitingReason;
    private Long durationMs;
    /** Latest replace-in-place activity; not an append-only timeline. */
    private String currentActivity;
    private List<DeliveryStepVO> steps;
    private StepUsageSummaryVO usage;
}
