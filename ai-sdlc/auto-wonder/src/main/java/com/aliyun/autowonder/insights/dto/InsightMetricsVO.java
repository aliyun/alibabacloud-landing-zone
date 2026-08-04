package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

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
