package com.aliyun.autowonder.scheduledtask.compat;

public enum V037MapperMode {
    LEGACY("autowonder-legacy"),
    SOURCE_AWARE("autowonder-source-aware");

    private final String databaseId;

    V037MapperMode(String databaseId) {
        this.databaseId = databaseId;
    }

    public String databaseId() {
        return databaseId;
    }
}
