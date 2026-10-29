package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class DispatchAttemptVO {
    private Long dispatchId;
    private Long agentId;
    private String agentName;
    private Long executorId;
    private String executorName;
    private String status;
    private String waitingReason;
    private String resumeMode;
    private String error;
    private Date startedAt;
    private Date endedAt;
    private Long durationMs;
    private List<StepTimingVO> stepTimings;
    private Boolean canContinue;
    private Boolean canPause;
    private Boolean cancelRequested;
    private Boolean stopPending;
    private Boolean forced;
    private DispatchControlCapabilitiesVO control;
}
