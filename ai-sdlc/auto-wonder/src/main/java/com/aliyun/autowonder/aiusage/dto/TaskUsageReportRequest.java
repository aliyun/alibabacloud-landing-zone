package com.aliyun.autowonder.aiusage.dto;

import com.alibaba.fastjson.annotation.JSONField;
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
        @JSONField(name = "input_tokens")
        @JsonProperty("input_tokens")
        private Long inputTokens;
        @JSONField(name = "output_tokens")
        @JsonProperty("output_tokens")
        private Long outputTokens;
        @JSONField(name = "cache_read_tokens")
        @JsonProperty("cache_read_tokens")
        private Long cacheReadTokens;
        @JSONField(name = "cache_write_tokens")
        @JsonProperty("cache_write_tokens")
        private Long cacheWriteTokens;
    }
}
