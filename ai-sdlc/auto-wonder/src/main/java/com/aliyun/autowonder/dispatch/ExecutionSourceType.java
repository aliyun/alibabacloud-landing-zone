package com.aliyun.autowonder.dispatch;

public enum ExecutionSourceType {
    WORKITEM,
    SCHEDULED_TASK,
    SCHEDULED_TASK_RUN;

    public static ExecutionSourceType valueOrWorkitem(String value) {
        return value == null || value.isBlank() ? WORKITEM : valueOf(value);
    }
}
