package com.aliyun.autowonder.scheduledtask;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ScheduledTaskDO {
    private Long id;
    private Long workspaceId;
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
    private String status;
    private Date nextFireAt;
    private Date lastFireAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
