package com.aliyun.autowonder.dispatch;

import lombok.Getter;
import com.aliyun.autowonder.workitem.AssignmentActor;

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
    private final AssignmentActor actor;

    public WorkitemAssignedEvent(long tenantId, long workitemId,
                                 Long sdlcStepId, Long agentId, int assignmentVersion, long userId) {
        this(tenantId, workitemId, sdlcStepId, agentId, assignmentVersion, userId,
                userId == 0L ? AssignmentActor.systemFallback() : AssignmentActor.human(userId, null));
    }

    public WorkitemAssignedEvent(long tenantId, long workitemId,
                                 Long sdlcStepId, Long agentId, int assignmentVersion, long userId,
                                 AssignmentActor actor) {
        this.tenantId = tenantId;
        this.workitemId = workitemId;
        this.sdlcStepId = sdlcStepId;
        this.agentId = agentId;
        this.assignmentVersion = assignmentVersion;
        this.userId = userId;
        this.actor = java.util.Objects.requireNonNull(actor, "actor");
    }
}
