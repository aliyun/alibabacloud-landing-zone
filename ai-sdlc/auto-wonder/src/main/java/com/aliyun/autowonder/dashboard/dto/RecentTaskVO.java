package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RecentTaskVO {
    private Long dispatchId;
    private String agentName;
    private String workitemTitle;
    private String status;
    private int durationMinutes;
    private String finishedAt;
}
