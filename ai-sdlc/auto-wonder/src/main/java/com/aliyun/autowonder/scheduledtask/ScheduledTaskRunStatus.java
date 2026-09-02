package com.aliyun.autowonder.scheduledtask;

public enum ScheduledTaskRunStatus {
    QUEUED,
    STARTING,
    WAITING_EXECUTOR,
    RUNNING,
    WAITING_HUMAN,
    PAUSED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELED,
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT
                || this == CANCELED || this == SKIPPED;
    }
}
