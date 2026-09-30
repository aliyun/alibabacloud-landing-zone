package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.ToString;

/**
 * Published by WorkitemServiceRestartHandler when an AGENT reassignment restarts the
 * formal delivery. Consumed by the dispatch module to stop old executions and enqueue a
 * new formal round idempotent on {@link #idempotencyKey()}.
 */
@Getter
@ToString
public class WorkitemDeliveryRestartedEvent {
    private final long tenantId;
    private final long workitemId;
    private final Long sdlcStepId;
    private final Long agentId;
    private final long userId;
    private final String idempotencyKey;
    private final java.util.Date scheduledStartAt;

    public WorkitemDeliveryRestartedEvent(long tenantId, long workitemId, Long sdlcStepId,
            Long agentId, long userId, String idempotencyKey, java.util.Date scheduledStartAt) {
        this.tenantId = tenantId;
        this.workitemId = workitemId;
        this.sdlcStepId = sdlcStepId;
        this.agentId = agentId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.scheduledStartAt = scheduledStartAt;
    }
}
