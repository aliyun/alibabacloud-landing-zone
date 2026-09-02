package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class WorkitemDO {
    private Long id;
    private Long tenantId;
    private String workType;
    private String title;
    private String contentMd;
    private Long templateId;
    private Long statusNodeId;
    private Long sdlcId;
    private Long currentStepId;
    private String assigneeType;
    private Long assigneeRef;
    private Long assignOperatorId;
    private Integer priority;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
    private String originType;
    private Long originId;
    private Date scheduledStartAt;
    private Date scheduledStartTriggeredAt;
    private String tags;
}
