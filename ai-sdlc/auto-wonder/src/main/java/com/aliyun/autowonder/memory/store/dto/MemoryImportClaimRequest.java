package com.aliyun.autowonder.memory.store.dto;

public record MemoryImportClaimRequest(long sourceId, String sanitizedContentSha256, String leaseId) {
    public MemoryImportClaimRequest(long sourceId, String sanitizedContentSha256) {
        this(sourceId, sanitizedContentSha256, null);
    }
}
