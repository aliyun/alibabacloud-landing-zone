package com.aliyun.autowonder.insights.participation;

import java.time.Instant;

public class HumanAgentParticipationFact {

    private final long workitemId;
    private final String title;
    private final Instant completedAt;
    private final long totalDurationSeconds;
    private final long humanDurationSeconds;
    private final long agentDurationSeconds;
    private String workType;
    private Instant createdAt;
    private boolean agentParticipated;
    private boolean inferredAssignment;
    private String exclusionReason;
    private String chainQuality = "OK";

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
    public void setExclusionReason(String value) {
        exclusionReason = value;
        // 被排除的链本身就有缺陷，质量标记不能停留在 OK。
        if (value != null && !value.isBlank() && (chainQuality == null || "OK".equals(chainQuality))) {
            chainQuality = value;
        }
    }
    public String chainQuality() { return chainQuality; }
    public void setChainQuality(String value) { chainQuality = value == null || value.isBlank() ? "OK" : value; }

    public long workitemId() {
        return workitemId;
    }

    public String title() {
        return title;
    }

    /** 旧快照没有该字段时为 null，按匹配所有工单类型处理。 */
    public String workType() {
        return workType;
    }

    public void setWorkType(String value) {
        workType = value;
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
