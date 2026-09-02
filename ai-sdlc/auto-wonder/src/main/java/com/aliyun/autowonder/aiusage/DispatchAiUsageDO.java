package com.aliyun.autowonder.aiusage;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DispatchAiUsageDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long dispatchId;
    private Long agentId;
    private Long executorId;
    private Long artifactId;
    private String stepId = "";
    private String provider;
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;
    private Long reasoningTokens;
    private java.math.BigDecimal credits;
    private Long totalTokens;
    private String rawJson;
    private Date usageAt;
    private Date gmtCreate;
    private Date gmtModified;
}
