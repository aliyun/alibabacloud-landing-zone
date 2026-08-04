package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkstationVO {
    private Long agentId;
    private String name;
    private String avatarUrl;
    private int runningTasks;
    private boolean busy;
}
