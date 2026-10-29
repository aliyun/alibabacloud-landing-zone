package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CostKpiVO {
    private int range;
    private String agentId;
    private BigDecimal total;
    private BigDecimal perTask;
    private BigDecimal perDay;
}
