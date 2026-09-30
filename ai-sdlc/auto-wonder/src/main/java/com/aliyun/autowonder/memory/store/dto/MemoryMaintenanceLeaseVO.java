package com.aliyun.autowonder.memory.store.dto;

public record MemoryMaintenanceLeaseVO(boolean acquired, String leaseId) {
    public static MemoryMaintenanceLeaseVO unavailable() {
        return new MemoryMaintenanceLeaseVO(false, null);
    }
}
