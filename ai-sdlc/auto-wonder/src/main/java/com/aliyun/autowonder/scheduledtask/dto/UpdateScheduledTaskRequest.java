package com.aliyun.autowonder.scheduledtask.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class UpdateScheduledTaskRequest {
    private Integer version;
    private String name;
    private String instructionMd;
    private Long squadId;
    private Long initialAgentId;
    private String scheduleType;
    private Date runAt;
    private String cronExpression;
    private String timezone;
    private String sessionMode;
    private String overlapPolicy;
    private String misfirePolicy;
    private Integer startDeadlineSeconds;
    private Integer affinityTimeoutSeconds;
}
