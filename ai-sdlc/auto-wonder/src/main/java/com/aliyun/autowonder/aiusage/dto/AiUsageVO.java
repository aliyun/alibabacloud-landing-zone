package com.aliyun.autowonder.aiusage.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiUsageVO {
    private String period;
    private String scene;
    private long callCount;
    private long inputTokens;
    private long outputTokens;
}
