package com.aliyun.autowonder.memory.store.dto;

public record MemoryImportSnapshotRequest(long sourceId, String sanitizedContentSha256,
                                          String sanitizedContent, long byteSize, int redactionCount) {
    public MemoryImportSnapshotRequest(long sourceId, String sanitizedContentSha256,
                                       String sanitizedContent, long byteSize) {
        this(sourceId, sanitizedContentSha256, sanitizedContent, byteSize, 0);
    }
}
