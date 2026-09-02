package com.aliyun.autowonder.scheduledtask.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class CreateScheduledTaskRequest {
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
    /** ACTIVE creates-and-enables; PAUSED saves without scheduling dispatch. Defaults to ACTIVE. */
    private String initialStatus;
}
