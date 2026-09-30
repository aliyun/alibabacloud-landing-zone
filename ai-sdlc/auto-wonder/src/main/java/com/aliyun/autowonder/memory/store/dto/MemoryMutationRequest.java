package com.aliyun.autowonder.memory.store.dto;

public record MemoryMutationRequest(Operation operation, String path, String newPath, String contentMd,
                                    Integer expectedVersion, String idempotencyKey, String maintenanceLeaseId) {
    public enum Operation { CREATE, UPDATE, DELETE }

    public static MemoryMutationRequest create(String path, String contentMd, String idempotencyKey) {
        return new MemoryMutationRequest(Operation.CREATE, path, path, contentMd, null, idempotencyKey, null);
    }

    public static MemoryMutationRequest update(String path, String contentMd, int expectedVersion,
                                               String idempotencyKey) {
        return update(path, path, contentMd, expectedVersion, idempotencyKey);
    }

    public static MemoryMutationRequest update(String path, String newPath, String contentMd,
                                               int expectedVersion, String idempotencyKey) {
        return new MemoryMutationRequest(Operation.UPDATE, path, newPath, contentMd,
                expectedVersion, idempotencyKey, null);
    }

    public static MemoryMutationRequest delete(String path, int expectedVersion, String idempotencyKey) {
        return new MemoryMutationRequest(Operation.DELETE, path, path, null, expectedVersion, idempotencyKey, null);
    }

    public MemoryMutationRequest(Operation operation, String path, String newPath, String contentMd,
                                 Integer expectedVersion, String idempotencyKey) {
        this(operation, path, newPath, contentMd, expectedVersion, idempotencyKey, null);
    }
}
