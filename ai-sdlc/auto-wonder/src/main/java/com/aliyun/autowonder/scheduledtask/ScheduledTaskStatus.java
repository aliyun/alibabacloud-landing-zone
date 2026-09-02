package com.aliyun.autowonder.scheduledtask;

public enum ScheduledTaskStatus {
    ACTIVE,
    PAUSED,
    EXHAUSTED,
    ARCHIVED;

    public boolean isSchedulable() {
        return this == ACTIVE;
    }

    public boolean isRetired() {
        return this == EXHAUSTED || this == ARCHIVED;
    }
}
