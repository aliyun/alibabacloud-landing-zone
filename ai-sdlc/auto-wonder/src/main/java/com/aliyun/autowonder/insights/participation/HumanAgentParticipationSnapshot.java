package com.aliyun.autowonder.insights.participation;

import java.time.Instant;
import java.util.List;

public class HumanAgentParticipationSnapshot {

    private int schemaVersion;
    private String generatedAt;
    private String dataThrough;
    private List<HumanAgentParticipationFact> items;

    public HumanAgentParticipationSnapshot() {}

    public HumanAgentParticipationSnapshot(int schemaVersion, String generatedAt,
                                            String dataThrough,
                                            List<HumanAgentParticipationFact> items) {
        this.schemaVersion = schemaVersion;
        this.generatedAt = generatedAt;
        this.dataThrough = dataThrough;
        this.items = items;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
    public String getDataThrough() { return dataThrough; }
    public void setDataThrough(String dataThrough) { this.dataThrough = dataThrough; }
    public List<HumanAgentParticipationFact> getItems() { return items; }
    public void setItems(List<HumanAgentParticipationFact> items) { this.items = items; }

    public static class FactEntry {
        private long workitemId;
        private String title;
        private String completedAt;
        private long totalDurationSeconds;
        private long humanDurationSeconds;
        private long agentDurationSeconds;

        public long getWorkitemId() { return workitemId; }
        public void setWorkitemId(long workitemId) { this.workitemId = workitemId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getCompletedAt() { return completedAt; }
        public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
        public long getTotalDurationSeconds() { return totalDurationSeconds; }
        public void setTotalDurationSeconds(long v) { this.totalDurationSeconds = v; }
        public long getHumanDurationSeconds() { return humanDurationSeconds; }
        public void setHumanDurationSeconds(long v) { this.humanDurationSeconds = v; }
        public long getAgentDurationSeconds() { return agentDurationSeconds; }
        public void setAgentDurationSeconds(long v) { this.agentDurationSeconds = v; }
    }
}
