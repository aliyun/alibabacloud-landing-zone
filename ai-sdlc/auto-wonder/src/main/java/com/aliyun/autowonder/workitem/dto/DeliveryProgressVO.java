package com.aliyun.autowonder.workitem.dto;

import com.aliyun.autowonder.aiusage.dto.WorkitemUsageSummaryVO;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class DeliveryProgressVO {
    /** All executions, including comment interactions and executions without a workflow step. */
    private List<DispatchAttemptVO> executions;
    private List<DeliveryStepVO> steps;
    private List<AgentDeliveryProgressVO> agents;
    private WorkflowPlanVO workflowPlan;
    private ProcessGraphVO processGraph;
    /** 从首次派发到当前/结束的墙钟时间；含数字人之间的交接与排队间隔。 */
    private Long totalDurationMs;
    /** 工单内全部执行轮次 + 绑定本工单的需求澄清累加的 credits；不含未绑定工单的对话。无正数消耗时为 null，前端据此不展示。 */
    private WorkitemUsageSummaryVO totalUsage;
    /** 交付闸门是否已关闭；关闭后仍保留必要的结束/强制结束动作。 */
    private Boolean closed;
    private Boolean canReopen;
    private String reopenReasonKey;
    private List<String> reopenReasonArgs;
}
