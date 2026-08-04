package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RealtimeDashboardVO {
    private KpiVO kpi;
    private InventoryVO inventory;
    private List<SquadLineVO> squads;
    private List<WorkstationVO> workstations;
    private HealthVO health;
    private List<RunningTaskVO> runningFeed;
    private List<RecentTaskVO> recentFeed;
    private String generatedAt;
}
