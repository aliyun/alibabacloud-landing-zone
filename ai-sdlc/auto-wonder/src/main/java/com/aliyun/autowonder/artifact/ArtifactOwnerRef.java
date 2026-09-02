package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.ExecutionSourceType;

import java.util.Objects;

public record ArtifactOwnerRef(ExecutionSourceType sourceType, long sourceId) {
    public ArtifactOwnerRef {
        Objects.requireNonNull(sourceType, "sourceType");
        if (sourceId <= 0) {
            throw new IllegalArgumentException("sourceId must be positive");
        }
    }
}
