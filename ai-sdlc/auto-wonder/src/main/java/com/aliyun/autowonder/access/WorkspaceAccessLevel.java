package com.aliyun.autowonder.access;

public enum WorkspaceAccessLevel {
    READ_ONLY(0),
    READ_WRITE(1),
    ADMIN(2);

    private final int rank;

    WorkspaceAccessLevel(int rank) {
        this.rank = rank;
    }

    public boolean allows(WorkspaceAccessLevel required) {
        return rank >= required.rank;
    }

    public static WorkspaceAccessLevel minimum(WorkspaceAccessLevel left, WorkspaceAccessLevel right) {
        return left.rank <= right.rank ? left : right;
    }
}
