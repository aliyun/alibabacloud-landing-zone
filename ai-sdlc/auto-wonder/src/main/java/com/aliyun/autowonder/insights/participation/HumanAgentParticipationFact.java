package com.aliyun.autowonder.insights.participation;

import java.time.Instant;

public class HumanAgentParticipationFact {

    private final long workitemId;
    private final String title;
    private final Instant completedAt;
    private final long totalDurationSeconds;
    private final long humanDurationSeconds;
    private final long agentDurationSeconds;

    public HumanAgentParticipationFact(long workitemId, String title, Instant completedAt,
                                       long totalDurationSeconds, long humanDurationSeconds,
                                       long agentDurationSeconds) {
        this.workitemId = workitemId;
        this.title = title;
        this.completedAt = completedAt;
        this.totalDurationSeconds = totalDurationSeconds;
        this.humanDurationSeconds = humanDurationSeconds;
        this.agentDurationSeconds = agentDurationSeconds;
    }

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
