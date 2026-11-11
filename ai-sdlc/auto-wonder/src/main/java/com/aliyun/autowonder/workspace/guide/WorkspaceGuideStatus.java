package com.aliyun.autowonder.workspace.guide;

/** 引导进度状态：首次进入为 PENDING，开始配置后为 IN_PROGRESS，结束只有 SKIPPED 和 COMPLETED 两种终态。 */
public enum WorkspaceGuideStatus {
    PENDING,
    IN_PROGRESS,
    SKIPPED,
    COMPLETED;

    public static WorkspaceGuideStatus from(String value) {
        if (value == null) {
            return null;
        }
        for (WorkspaceGuideStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    public boolean terminal() {
        return this == SKIPPED || this == COMPLETED;
    }
}
