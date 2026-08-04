package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiQuotaDO extends BaseDO {
    private Long tenantId;
    private String periodType;
    private Long maxCalls;
    private Long maxTokens;
    private Integer concurrencyLimit;
}
