package com.aliyun.autowonder.aiusage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 工单内某一数字人某一轮执行(run)的 credits 消耗。 */
@Getter
@Setter
public class WorkitemUsageRunVO {
    private Long agentId;
    private String agentName;
    /** 该数字人在本工单内的执行轮次，从 1 开始。 */
    private Integer runIndex;
    /** 展示用标签，形如 `DEV run-1`。 */
    private String label;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String labelKind;
    private BigDecimal credits;
}
