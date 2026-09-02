package com.aliyun.autowonder.scheduledtask;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ScheduledTaskRunDO {
    private Long id;
    private Long workspaceId;
    private Long scheduledTaskId;
    private String triggerKey;
    private String triggerType;
    private Date scheduledAt;
    private Date startedAt;
    private Date finishedAt;
    private String status;
    private String skipReason;
    private Long squadId;
    private Long initialAgentId;
    private Long currentAgentId;
    private Long sdlcId;
    private Long currentStepId;
    private String sessionMode;
    private Long resumeFromRunId;
    private Integer degradedResume;
    private String degradedReason;
    private String executionSnapshotJson;
    private String resultSummary;
    private String error;
    private Long ownerId;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer version;
}
