package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KpiVO {
    private int runningDispatches;
    private int todayCompletedTasks;
    private int weekCompletedTasks;
    private int avgTaskDurationMinutes;
    private int inProgressWorkitems;
    private int queuedDispatches;
    private int activeSquads;
    private int onlineAgents;
    private double avgLoad;
}
