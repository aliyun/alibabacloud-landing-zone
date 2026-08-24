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
    /** 从首次派发到当前/结束的墙钟时间；含数字人之间的交接与排队间隔。 */
    private Long totalDurationMs;
}
