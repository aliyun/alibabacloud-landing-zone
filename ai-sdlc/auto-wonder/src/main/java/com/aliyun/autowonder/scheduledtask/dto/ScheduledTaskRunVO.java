package com.aliyun.autowonder.scheduledtask.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ScheduledTaskRunVO {
    private Long id;
    private Long scheduledTaskId;
    private String triggerType;
    private Date scheduledAt;
    private Date startedAt;
    private Date finishedAt;
    private String status;
    private String skipReason;
    private Long currentAgentId;
    private Long sdlcId;
    private Long currentStepId;
    private boolean degradedResume;
    private String degradedReason;
    private String resultSummary;
    private String error;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}
