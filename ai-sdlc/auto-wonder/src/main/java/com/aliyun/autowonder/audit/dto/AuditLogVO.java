package com.aliyun.autowonder.audit.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AuditLogVO {
    private Long id;
    private Long actorId;
    private String actorType;
    private String actorName;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String detailJson;
    private Date gmtCreate;
}
