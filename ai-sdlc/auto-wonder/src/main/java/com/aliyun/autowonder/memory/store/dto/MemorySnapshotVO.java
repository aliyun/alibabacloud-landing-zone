package com.aliyun.autowonder.memory.store.dto;

import java.util.List;

public record MemorySnapshotVO(String composedIndex, List<Store> stores) {
    public record Store(long id, String scope, long ownerRef, long revision, String permission,
                        List<Document> documents) {
    }

    public record Document(String path, String contentMd, int version) {
    }
}
