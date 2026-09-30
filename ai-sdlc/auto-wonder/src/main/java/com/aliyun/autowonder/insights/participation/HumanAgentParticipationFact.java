package com.aliyun.autowonder.insights.participation;

import java.time.Instant;

public class HumanAgentParticipationFact {

    private final long workitemId;
    private final String title;
    private final Instant completedAt;
    private final long totalDurationSeconds;
    private final long humanDurationSeconds;
    private final long agentDurationSeconds;
    private Instant createdAt;
    private boolean agentParticipated;
    private boolean inferredAssignment;
    private String exclusionReason;
    private Long executionSeconds;
    private String executionStatus = "MISSING";

    public HumanAgentParticipationFact(long workitemId, String title, Instant completedAt,
                                       long totalDurationSeconds, long humanDurationSeconds,
                                       long agentDurationSeconds) {
        this.workitemId = workitemId;
        this.title = title;
        this.completedAt = completedAt;
        this.totalDurationSeconds = totalDurationSeconds;
        this.humanDurationSeconds = humanDurationSeconds;
        this.agentDurationSeconds = agentDurationSeconds;
        this.agentParticipated = agentDurationSeconds > 0;
        this.createdAt = completedAt.minusSeconds(totalDurationSeconds);
    }

    public Instant createdAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public boolean agentParticipated() { return agentParticipated; }
    public void setAgentParticipated(boolean value) { agentParticipated = value; }
    public boolean inferredAssignment() { return inferredAssignment; }
    public void setInferredAssignment(boolean value) { inferredAssignment = value; }
    public String exclusionReason() { return exclusionReason; }
    public void setExclusionReason(String value) { exclusionReason = value; }
    public Long executionSeconds() { return executionSeconds; }
    public String executionStatus() { return executionStatus; }
    public void setExecution(Long seconds, String status) { executionSeconds = seconds; executionStatus = status; }

    public long workitemId() {
        return workitemId;
    }

    public String title() {
        return title;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public long totalDurationSeconds() {
        return totalDurationSeconds;
    }

    public long humanDurationSeconds() {
        return humanDurationSeconds;
    }

    public long agentDurationSeconds() {
        return agentDurationSeconds;
    }
}
