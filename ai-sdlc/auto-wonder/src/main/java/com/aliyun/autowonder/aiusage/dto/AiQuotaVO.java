package com.aliyun.autowonder.aiusage.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiQuotaVO {
    private String periodType;
    private Long maxCalls;
    private Long maxTokens;
    private Integer concurrencyLimit;
}
