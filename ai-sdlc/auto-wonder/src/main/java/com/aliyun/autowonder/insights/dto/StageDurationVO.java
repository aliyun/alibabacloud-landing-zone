package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class StageDurationVO {
    private String startDate;
    private String endDate;
    private String timezone;
    private String calculatedAt;
    private int executionCount;
    private long workitemCount;
    private int measuredExecutionCount;
    private int missingDurationCount;
    private long totalDurationMs;
    private List<Stage> stages;

    @Getter
    @Setter
    public static class Stage {
        private String key;
        private Long sdlcId;
        private String sdlcName;
        private Long stepId;
        private Integer stepOrder;
        private String name;
        private int sampleCount;
        private int completedCount;
        private int activeCount;
        private int incompleteCount;
        private int reusedCount;
        private int skippedCount;
        private int missingDurationCount;
        private long totalDurationMs;
        private Long averageDurationMs;
        private Long medianDurationMs;
        private Long p90DurationMs;
        private List<Sample> slowest;
    }

    @Getter
    @Setter
    public static class Sample {
        private long dispatchId;
        private long workitemId;
        private String title;
        private String agentName;
        private String createdAt;
        private String status;
        private Long durationMs;
    }
}
