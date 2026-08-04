package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class DeliveryProgressVO {
    private List<DeliveryStepVO> steps;
    private List<AgentDeliveryProgressVO> agents;
    private WorkflowPlanVO workflowPlan;
    private ProcessGraphVO processGraph;
}
