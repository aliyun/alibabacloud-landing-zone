package com.aliyun.autowonder.notification;

final class WorkspaceAccessNotifyText {

    private WorkspaceAccessNotifyText() {
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    /** The workspace may have been deleted mid-review, in which case the event carries a null name. */
    static String workspaceLabel(String workspaceName) {
        return workspaceName == null || workspaceName.isBlank() ? "未命名工作空间" : workspaceName;
    }

    static String accessLevelLabel(String accessLevel) {
        return switch (safe(accessLevel)) {
            case "READ_ONLY" -> "只读";
            case "READ_WRITE" -> "读写";
            case "ADMIN" -> "管理员";
            default -> safe(accessLevel);
        };
    }

    /**
     * Shared by the requested and cancelled listeners so both emit one identical rule:
     * {@code tab=requests} opens the pending-approval pane, and {@code workspaceId} lands the
     * reviewer in the workspace the request belongs to instead of whichever one they last used.
     * AppLayout strips {@code workspaceId} after switching and keeps {@code tab}.
     */
    static String reviewLink(long tenantId) {
        return "/settings/members?tab=requests&workspaceId=" + tenantId;
    }
}
