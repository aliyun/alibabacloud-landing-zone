package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ExecutionControlVO {
    private String action;
    private String phase;
    private String origin;
    private Long dispatchId;
    private Long agentId;
    private String agentName;
    private Long requestedById;
    private String requestedByName;
    private Long sourceDispatchId;
    private Long targetDispatchId;
    private Long supersedesRequestEventId;
    private Long requestEventId;
    private Long parentRequestEventId;
    private Boolean stopPending;
    private Boolean forced;
    private String reason;
    private String mode;
    private List<Long> affectedDispatchIds;
}
