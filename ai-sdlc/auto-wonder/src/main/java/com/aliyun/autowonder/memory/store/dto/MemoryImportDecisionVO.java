package com.aliyun.autowonder.memory.store.dto;

public record MemoryImportDecisionVO(boolean uploadRequired, long sourceId, String reason,
                                     boolean curationRequired, String sanitizedContentSha256) {
    public MemoryImportDecisionVO(boolean uploadRequired, long sourceId, String reason) {
        this(uploadRequired, sourceId, reason, uploadRequired, null);
    }
}
