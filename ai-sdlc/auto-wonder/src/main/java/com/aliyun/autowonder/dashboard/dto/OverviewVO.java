package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OverviewVO {
    private OverviewKpiVO kpi = new OverviewKpiVO();
    private InventoryVO inventory = new InventoryVO();
    private String generatedAt;
}
