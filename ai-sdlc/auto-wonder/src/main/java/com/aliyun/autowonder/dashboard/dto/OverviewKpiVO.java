package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OverviewKpiVO {
    private int todayCreatedWorkitems;
    private int runningWorkitems;
    private int todayCompletedTasks;
    private int weekCompletedTasks;
}
