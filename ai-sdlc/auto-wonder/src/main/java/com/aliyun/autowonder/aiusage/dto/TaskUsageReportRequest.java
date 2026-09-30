package com.aliyun.autowonder.aiusage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TaskUsageReportRequest {
    private List<TaskUsageEntry> usage;

    @Getter
    @Setter
    public static class TaskUsageEntry {
        private String provider;
        private String model;
        @JsonProperty("step_id")
        private String stepId;
        @JsonProperty("input_tokens")
        private Long inputTokens;
        @JsonProperty("output_tokens")
        private Long outputTokens;
        @JsonProperty("cache_read_tokens")
        private Long cacheReadTokens;
        @JsonProperty("cache_write_tokens")
        private Long cacheWriteTokens;
        @JsonProperty("reasoning_tokens")
        private Long reasoningTokens;
        private Double credits;
    }
}
