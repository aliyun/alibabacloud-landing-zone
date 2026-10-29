package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class InsightMetricsVO {
    private CostMetrics cost;
    private EfficiencyMetrics efficiency;
    private StabilityMetrics stability;
    private SecurityMetrics security;

    @Getter
    @Setter
    public static class CostMetrics {
        private long totalTokens;
        private long avgTokensPerTask;
        private long dailyAvg;
        private List<Integer> trend;
        private BigDecimal totalCredits;
        private BigDecimal avgCreditsPerTask;
        private BigDecimal dailyAvgCredits;
        private List<BigDecimal> creditsTrend;
        /** 按数字员工的 credits 明细（派发 + 全部对话）；旧前端忽略该字段。 */
        private List<WorkerCost> byWorker;
        /** 按来源的 credits 构成（固定五类，过滤 0 值）；旧前端忽略该字段。 */
        private List<CreditSource> bySource;
    }

    @Getter
    @Setter
    public static class WorkerCost {
        private Long agentId;
        private String agentName;
        private BigDecimal credits;
        /** credits 中来自会话的部分。 */
        private BigDecimal conversationCredits;
    }

    @Getter
    @Setter
    public static class CreditSource {
        /** DISPATCH / WORKITEM_CLARIFICATION / PLATFORM_ASSISTANT / IM / OTHER_CONVERSATION */
        private String source;
        private BigDecimal credits;
    }

    @Getter
    @Setter
    public static class EfficiencyMetrics {
        private double completionRate;
        private int totalTasks;
        private int completedTasks;
        private int avgDurationMinutes;
        private List<Integer> trend;
    }

    @Getter
    @Setter
    public static class StabilityMetrics {
        private double successRate;
        private int retryCount;
        private int blockedCount;
        private List<Integer> trend;
    }

    @Getter
    @Setter
    public static class SecurityMetrics {
        private int highRiskOps;
        private double complianceRate;
        private int auditBlocks;
        private List<Integer> trend;
    }
}
