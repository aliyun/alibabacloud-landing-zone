package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DispatchAttemptVO {
    private Long dispatchId;
    private String executorName;
    private String status;
    private String resumeMode;
    private String error;
    private Date startedAt;
    private Long durationMs;
    private Boolean canContinue;
    private Boolean canPause;
}
