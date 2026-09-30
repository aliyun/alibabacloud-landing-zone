package com.aliyun.autowonder.memory.store.dto;

public record MemoryImportClaimVO(boolean acquired, long sourceId, String sanitizedContentSha256, String leaseId,
                                  String currentContent, String previousContent, int redactionCount) {
    public static MemoryImportClaimVO unavailable(long sourceId, String hash) {
        return new MemoryImportClaimVO(false, sourceId, hash, null, null, null, 0);
    }
}
