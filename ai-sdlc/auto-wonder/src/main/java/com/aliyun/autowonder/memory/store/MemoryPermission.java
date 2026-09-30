package com.aliyun.autowonder.memory.store;

public enum MemoryPermission {
    NONE,
    READ,
    WRITE,
    ADMIN;

    public boolean includes(MemoryPermission required) {
        return ordinal() >= required.ordinal();
    }

    public static MemoryPermission strongest(MemoryPermission left, MemoryPermission right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
