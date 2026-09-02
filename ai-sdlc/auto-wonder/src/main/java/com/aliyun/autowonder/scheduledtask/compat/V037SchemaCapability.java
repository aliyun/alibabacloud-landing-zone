package com.aliyun.autowonder.scheduledtask.compat;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record V037SchemaCapability(
        V037SchemaMode mode,
        V037MapperMode mapperMode,
        boolean sourceAwareColumnsReady,
        boolean scheduledAvailable,
        boolean scheduledDataExists,
        Set<String> missingObjects,
        Instant checkedAt) {

    public V037SchemaCapability(
            V037SchemaMode mode,
            V037MapperMode mapperMode,
            boolean sourceAwareColumnsReady,
            boolean scheduledAvailable,
            boolean scheduledDataExists,
            Set<String> missingObjects,
            Instant checkedAt) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.mapperMode = Objects.requireNonNull(mapperMode, "mapperMode");
        this.sourceAwareColumnsReady = sourceAwareColumnsReady;
        this.scheduledAvailable = scheduledAvailable;
        this.scheduledDataExists = scheduledDataExists;
        this.missingObjects = Set.copyOf(Objects.requireNonNull(missingObjects, "missingObjects"));
        this.checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
