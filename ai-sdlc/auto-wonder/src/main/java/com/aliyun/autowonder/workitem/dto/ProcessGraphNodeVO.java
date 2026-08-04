package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ProcessGraphNodeVO {
    private String key;
    private Long dispatchId;
    private Long agentId;
    private String agentName;
    private Long stepId;
    private String stepName;
    private String status;
    private Date startedAt;
    private Long durationMs;
    private String error;
    private Long triggerCommentId;
}
