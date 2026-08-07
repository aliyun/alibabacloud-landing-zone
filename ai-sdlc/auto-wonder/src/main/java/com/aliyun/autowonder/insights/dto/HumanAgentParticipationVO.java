package com.aliyun.autowonder.insights.dto;

import java.util.List;

public class HumanAgentParticipationVO {

    private boolean available;
    private String generatedAt;
    private String dataThrough;
    private boolean refreshTriggered;
    private int sampleSize;
    private DurationSummary average;
    private P90Workitem p90;
    private List<TrendEntry> trend;

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
    public String getDataThrough() { return dataThrough; }
    public void setDataThrough(String dataThrough) { this.dataThrough = dataThrough; }
    public boolean isRefreshTriggered() { return refreshTriggered; }
    public void setRefreshTriggered(boolean refreshTriggered) { this.refreshTriggered = refreshTriggered; }
    public int getSampleSize() { return sampleSize; }
    public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
    public DurationSummary getAverage() { return average; }
    public void setAverage(DurationSummary average) { this.average = average; }
    public P90Workitem getP90() { return p90; }
    public void setP90(P90Workitem p90) { this.p90 = p90; }
    public List<TrendEntry> getTrend() { return trend; }
    public void setTrend(List<TrendEntry> trend) { this.trend = trend; }

    public static class DurationSummary {
        private long totalDurationSeconds;
        private long humanDurationSeconds;
        private long agentDurationSeconds;

        public DurationSummary() {}
        public DurationSummary(long total, long human, long agent) {
            this.totalDurationSeconds = total;
            this.humanDurationSeconds = human;
            this.agentDurationSeconds = agent;
        }

        public long getTotalDurationSeconds() { return totalDurationSeconds; }
        public void setTotalDurationSeconds(long v) { this.totalDurationSeconds = v; }
        public long getHumanDurationSeconds() { return humanDurationSeconds; }
        public void setHumanDurationSeconds(long v) { this.humanDurationSeconds = v; }
        public long getAgentDurationSeconds() { return agentDurationSeconds; }
        public void setAgentDurationSeconds(long v) { this.agentDurationSeconds = v; }
    }

    public static class P90Workitem {
        private long workitemId;
        private String title;
        private String completedAt;
        private long totalDurationSeconds;
        private long humanDurationSeconds;
        private long agentDurationSeconds;

        public long getWorkitemId() { return workitemId; }
        public void setWorkitemId(long v) { this.workitemId = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { this.title = v; }
        public String getCompletedAt() { return completedAt; }
        public void setCompletedAt(String v) { this.completedAt = v; }
        public long getTotalDurationSeconds() { return totalDurationSeconds; }
        public void setTotalDurationSeconds(long v) { this.totalDurationSeconds = v; }
        public long getHumanDurationSeconds() { return humanDurationSeconds; }
        public void setHumanDurationSeconds(long v) { this.humanDurationSeconds = v; }
        public long getAgentDurationSeconds() { return agentDurationSeconds; }
        public void setAgentDurationSeconds(long v) { this.agentDurationSeconds = v; }
    }

    public static class TrendEntry {
        private String label;
        private long averageTotalSeconds;
        private long averageHumanSeconds;
        private long averageAgentSeconds;

        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }
        public long getAverageTotalSeconds() { return averageTotalSeconds; }
        public void setAverageTotalSeconds(long v) { this.averageTotalSeconds = v; }
        public long getAverageHumanSeconds() { return averageHumanSeconds; }
        public void setAverageHumanSeconds(long v) { this.averageHumanSeconds = v; }
        public long getAverageAgentSeconds() { return averageAgentSeconds; }
        public void setAverageAgentSeconds(long v) { this.averageAgentSeconds = v; }
    }
}
