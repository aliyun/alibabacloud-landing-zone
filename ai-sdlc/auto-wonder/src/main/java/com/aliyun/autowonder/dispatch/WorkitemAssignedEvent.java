package com.aliyun.autowonder.dispatch;

import lombok.Getter;

/**
 * Published by WorkitemService when a workitem is assigned to an AGENT.
 * Consumed by DispatchAssignmentListener to bootstrap a dispatch — the event
 * seam that breaks the WorkitemService <-> DispatchService bean cycle.
 */
@Getter
public class WorkitemAssignedEvent {
    private final long tenantId;
    private final long workitemId;
    private final Long sdlcStepId;
    private final Long agentId;
    private final int assignmentVersion;
    private final long userId;

    public WorkitemAssignedEvent(long tenantId, long workitemId,
                                 Long sdlcStepId, Long agentId, int assignmentVersion, long userId) {
        this.tenantId = tenantId;
        this.workitemId = workitemId;
        this.sdlcStepId = sdlcStepId;
        this.agentId = agentId;
        this.assignmentVersion = assignmentVersion;
        this.userId = userId;
    }
}
