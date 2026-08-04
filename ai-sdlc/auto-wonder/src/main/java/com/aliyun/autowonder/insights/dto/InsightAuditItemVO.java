package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InsightAuditItemVO {
    private String timestamp;
    private String worker;
    private String eventType;
    private String detail;
    private String riskLevel;
}
