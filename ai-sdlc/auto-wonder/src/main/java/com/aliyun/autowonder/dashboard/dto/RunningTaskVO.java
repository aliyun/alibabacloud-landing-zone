package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RunningTaskVO {
    private Long dispatchId;
    private Long agentId;
    private String agentName;
    private Long workitemId;
    private String workitemTitle;
    private String stepName;
    private int runningMinutes;
}
