package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import java.util.Locale;

final class WorkspaceAccessNotifyText {

    private WorkspaceAccessNotifyText() {
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    /** The workspace may have been deleted mid-review, in which case the event carries a null name. */
    static String workspaceLabel(String workspaceName) {
        return workspaceLabel(workspaceName, Locale.CHINA);
    }

    static String workspaceLabel(String workspaceName, Locale locale) {
        return workspaceName == null || workspaceName.isBlank()
                ? SystemMessages.render("notification.workspace_fallback", null, "未命名工作空间", locale) : workspaceName;
    }

    static String accessLevelLabel(String accessLevel) {
        return accessLevelLabel(accessLevel, Locale.CHINA);
    }

    static String accessLevelLabel(String accessLevel, Locale locale) {
        return switch (safe(accessLevel)) {
            case "READ_ONLY" -> SystemMessages.render("notification.role.viewer", null, "只读者", locale);
            case "READ_WRITE" -> SystemMessages.render("notification.role.collaborator", null, "读写者", locale);
            case "ADMIN" -> SystemMessages.render("notification.access.admin", null, "管理员", locale);
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
