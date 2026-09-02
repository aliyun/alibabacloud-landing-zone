package com.aliyun.autowonder.dispatch.subject;

import com.aliyun.autowonder.dispatch.ExecutionSourceType;

import java.util.Objects;

/** Stable identity of an object that can be executed by the dispatch kernel. */
public record ExecutionSubjectRef(ExecutionSourceType type, long id) {
    public ExecutionSubjectRef {
        Objects.requireNonNull(type, "type");
        if (type != ExecutionSourceType.WORKITEM
                && type != ExecutionSourceType.SCHEDULED_TASK_RUN) {
            throw new IllegalArgumentException("source type is not executable: " + type);
        }
        if (id <= 0) {
            throw new IllegalArgumentException("source id must be positive");
        }
    }
}
