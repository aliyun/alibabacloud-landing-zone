package com.aliyun.autowonder.scheduledtask.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter
@Setter
public class ScheduledTaskRunDetailVO extends ScheduledTaskRunVO {
    private Long squadId;
    private Long initialAgentId;
    private String sessionMode;
    private Long resumeFromRunId;
    private Long ownerId;
    /** Sanitized immutable execution facts for Run observability; never exposes frozen credentials or memory. */
    private Map<String, Object> snapshot;
    private Long executorId;
}
