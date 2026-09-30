package com.aliyun.autowonder.memory.store.dto;

public record MemoryImportManifestRequest(String providerFamily, String logicalPath,
                                          String installationFingerprint,
                                          String sanitizedContentSha256, long byteSize,
                                          String observationStatus) {
    public MemoryImportManifestRequest(String providerFamily, String logicalPath,
                                       String installationFingerprint,
                                       String sanitizedContentSha256, long byteSize) {
        this(providerFamily, logicalPath, installationFingerprint, sanitizedContentSha256, byteSize, "OBSERVED");
    }
}
