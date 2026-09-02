package com.aliyun.autowonder.scheduledtask.compat;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record V037SchemaInventory(
        boolean probeSucceeded,
        boolean anyV037Object,
        boolean sourceAwareColumnsReady,
        boolean scheduledObjectsReady,
        boolean scheduledDataExists,
        Set<String> missingObjects,
        String failureReason,
        Instant checkedAt) {

    public V037SchemaInventory(
            boolean probeSucceeded,
            boolean anyV037Object,
            boolean sourceAwareColumnsReady,
            boolean scheduledObjectsReady,
            boolean scheduledDataExists,
            Set<String> missingObjects,
            String failureReason,
            Instant checkedAt) {
        this.probeSucceeded = probeSucceeded;
        this.anyV037Object = anyV037Object;
        this.sourceAwareColumnsReady = sourceAwareColumnsReady;
        this.scheduledObjectsReady = scheduledObjectsReady;
        this.scheduledDataExists = scheduledDataExists;
        this.missingObjects = Set.copyOf(Objects.requireNonNull(missingObjects, "missingObjects"));
        this.failureReason = failureReason;
        this.checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public static V037SchemaInventory preV037() {
        return new V037SchemaInventory(
                true, false, false, false, false, Set.of(), null, Instant.now());
    }

    public static V037SchemaInventory sourceAwarePartial(Set<String> missingObjects) {
        return new V037SchemaInventory(
                true, true, true, false, false, missingObjects, null, Instant.now());
    }

    public static V037SchemaInventory v037Ready() {
        return new V037SchemaInventory(
                true, true, true, true, false, Set.of(), null, Instant.now());
    }

    public static V037SchemaInventory legacyPartialWithScheduledData() {
        return new V037SchemaInventory(
                true, true, false, false, true,
                Set.of("dispatch.source_type"), null, Instant.now());
    }

    public static V037SchemaInventory failed(String failureReason) {
        return new V037SchemaInventory(
                false, false, false, false, false, Set.of(), failureReason, Instant.now());
    }
}
