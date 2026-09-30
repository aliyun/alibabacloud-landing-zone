package com.aliyun.autowonder.memory.store.dto;

public record MemoryDocumentVO(Long id, Long storeId, String path, String contentMd,
                               String contentSha256, long byteSize, int version) {
}
