package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class WorkitemVO {
    private Long id;
    private String workType;
    private String title;
    private String contentMd;
    private Long templateId;
    private Long statusNodeId;
    private String statusName;
    private Long sdlcId;
    private String sdlcName;
    private String assigneeType;
    private Long assigneeRef;
    private String assigneeName;
    private String assigneeDisplayName;
    private Long creatorId;
    private String creatorName;
    private String creatorDisplayName;
    private Integer priority;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
    /** Delivery health: "OK" or "STUCK" (in-progress workitem whose latest dispatch failed or stalled). */
    private String health;
    /** Human-readable reason when health is STUCK; null otherwise. */
    private String healthReason;
    /** True when automated delivery has completed and the workitem is waiting for human decision. */
    private Boolean pendingDecision;
    /** Workitem source for deletion eligibility: "NATIVE" or "EXTERNAL". */
    private String sourceType;
    /** Whether the current workitem can be deleted by a human user. */
    private Boolean deletable;
    /** Human-readable reason when deletable is false. */
    private String deletableReason;
}
