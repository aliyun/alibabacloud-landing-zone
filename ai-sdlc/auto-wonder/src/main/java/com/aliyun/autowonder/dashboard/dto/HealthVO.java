package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HealthVO {
    private double successRate;
    private int failedOrTimeout;
    private int retries;
    private int avgDurationMinutes;
}
