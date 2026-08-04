package com.aliyun.autowonder.audit.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditLogQuery {
    private String module;
    private String action;
    private Long actorId;
    private String targetType;
    private Long targetId;
    private String startTime;
    private String endTime;
    private String keyword;
}
