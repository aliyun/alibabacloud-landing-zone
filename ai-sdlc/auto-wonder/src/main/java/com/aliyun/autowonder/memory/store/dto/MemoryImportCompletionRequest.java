package com.aliyun.autowonder.memory.store.dto;

public record MemoryImportCompletionRequest(long sourceId, String sanitizedContentSha256, int redactionCount,
                                            String leaseId) {
    public MemoryImportCompletionRequest(long sourceId, String sanitizedContentSha256) {
        this(sourceId, sanitizedContentSha256, 0, null);
    }

    public MemoryImportCompletionRequest(long sourceId, String sanitizedContentSha256, int redactionCount) {
        this(sourceId, sanitizedContentSha256, redactionCount, null);
    }
}
