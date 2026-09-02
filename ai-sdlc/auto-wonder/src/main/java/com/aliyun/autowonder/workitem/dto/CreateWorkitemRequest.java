package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class CreateWorkitemRequest {
    private String workType;
    private String title;
    private String contentMd;
    private Integer priority;
    /**
     * Optional assignee type (e.g. {@code HUMAN} or {@code AGENT}). When omitted the
     * workitem is assigned to the creator, preserving the original behavior. When
     * present, {@link com.aliyun.autowonder.workitem.WorkitemService#create} delegates
     * to {@code assign(...)} so the workitem receives the same delivery-start treatment
     * as a separate post-create assignment.
     */
    private String assigneeType;
    private Long assigneeRef;
    private Long sdlcId;
    private Long squadId;
    /** Planned start time for agent assignments; null means dispatch immediately. */
    private Date scheduledStartAt;
}
