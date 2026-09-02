package com.aliyun.autowonder.aiusage.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class StepUsageSummaryVO {
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long reasoningTokens;
    private BigDecimal credits;
}
