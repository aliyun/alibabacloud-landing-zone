package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class WorkitemVO {
    private Long id;
    private String workType;
    private String title;
    /** Latest dispatch state, independent of the business status and kanban column. */
    private String executionStatus;
    private String contentMd;
    private Long templateId;
    private Long statusNodeId;
    private String statusName;
    /**
     * Server-computed unified kanban classification (WorkitemClassificationEvaluator):
     * NEW / IN_PROGRESS / PENDING_DECISION / DONE / CANCELED. CANCELED items are hidden from the
     * board and only visible via the explicit filter. The status name is display-only.
     */
    private String statusCategory;
    /**
     * 越界流转提示（规格 3.3）：流转成功但不在模板 transitions 推荐范围内时返回
     * 「该流转不在模板推荐范围内」，页面据此提示；正常流转为 null。
     */
    private String transitionWarning;
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
    /**
     * True when the workitem is waiting for a human decision: in-flight (INIT/IN_PROGRESS), assigned
     * to a human, a dispatch has actually started before, and none is currently active. Identical to
     * the 待决策 kanban column and the 需人工 tag condition (statusCategory = PENDING_DECISION).
     */
    private Boolean pendingDecision;
    /** Workitem source for deletion eligibility: "NATIVE" or "EXTERNAL". */
    private String sourceType;
    /** Preferred external provider for list/card presentation, for example AONE. */
    private String sourceProvider;
    /** Preferred external workitem URL for list/card presentation. */
    private String sourceUrl;
    /** Whether the current workitem can be deleted by a human user. */
    private Boolean deletable;
    /** Human-readable reason when deletable is false. */
    private String deletableReason;
    /** Server-owned backlink when an agent created this item from a Run. */
    private WorkitemOriginVO origin;
    /** External business collaboration snapshot; null for native workitems. */
    private ExternalCollaborationVO externalCollaboration;
    /** Source-side creator for imported workitems; the local creatorId remains the import operator. */
    private ExternalPrincipalVO sourceCreator;
    /** Planned agent delivery start time; null means immediate or already started. */
    private Date scheduledStartAt;
    /** Actual time the planned scheduled start fired; null when never triggered. */
    private Date scheduledStartTriggeredAt;
    /**
     * Derived scheduled-workitem phase: PENDING (待触发), READY (待处理), RUNNING (执行中) or DONE (已完成).
     * Null for workitems that were never scheduled. See WorkitemScheduledPhase.
     */
    private String scheduledPhase;
    /** Workitem tags; empty list when unset. */
    private List<String> tags;
    /** Whether the current user watches this workitem's progress. */
    private Boolean watched;
}
