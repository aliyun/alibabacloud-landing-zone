package com.aliyun.autowonder.access;

public enum OrgAccessLevel {
    READ_ONLY(0),
    READ_WRITE(1),
    ADMIN(2);

    private final int rank;

    OrgAccessLevel(int rank) {
        this.rank = rank;
    }

    public boolean allows(OrgAccessLevel required) {
        return rank >= required.rank;
    }

    public static OrgAccessLevel minimum(OrgAccessLevel left, OrgAccessLevel right) {
        return left.rank <= right.rank ? left : right;
    }
}
