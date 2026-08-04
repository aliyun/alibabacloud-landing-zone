package com.aliyun.autowonder.dispatch;

public record HandoffResult(Status status, Long targetRef, Long downstreamDispatchId,
        String reasonCode, String message) {

    public enum Status {
        AGENT_DISPATCHED,
        HUMAN_ASSIGNED,
        REJECTED
    }

    public static HandoffResult agent(long agentId, long dispatchId) {
        return new HandoffResult(Status.AGENT_DISPATCHED, agentId, dispatchId, null, "handoff accepted");
    }

    public static HandoffResult human(long userId, String reasonCode) {
        return new HandoffResult(Status.HUMAN_ASSIGNED, userId, null, reasonCode, "assigned to human");
    }

    public static HandoffResult rejected(String reasonCode, String message) {
        return new HandoffResult(Status.REJECTED, null, null, reasonCode, message);
    }
}
