package com.aliyun.autowonder.im.notification;

import org.springframework.stereotype.Component;

@Component
public class ImNotificationFormatter {
    private static final int COMMENT_SNIPPET_MAX_LENGTH = 30;
    private static final String OUTCOME_APPROVED = "APPROVED";

    public String format(ImNotificationMessageContext context, ImNotificationTask task) {
        String normalizedBaseUrl = trimTrailingSlash(context.baseUrl());
        if (ImNotificationTask.TYPE_COMMENT_MENTION.equals(task.notificationType())) {
            if (task.isScheduledTaskRun()) {
                return "### 评论中提到了你\n\n"
                        + "**工作空间**：" + safeText(context.workspaceName()) + "\n"
                        + "**定时任务**：" + safeText(task.workitemTitle()) + "\n"
                        + "**评论人**：" + safeText(task.actorDisplayName()) + "\n"
                        + commentSnippetBlock(task.commentContentMd())
                        + "[查看执行记录](" + scheduledTaskRunUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()) + ")";
            }
            return "### 评论中提到了你\n\n"
                    + "**工作空间**：" + safeText(context.workspaceName()) + "\n"
                    + "**工单**：" + safeText(task.workitemTitle()) + "\n"
                    + "**评论人**：" + safeText(task.actorDisplayName()) + "\n"
                    + commentSnippetBlock(task.commentContentMd())
                    + "[查看工单](" + workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()) + ")";
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST.equals(task.notificationType())) {
            return "### 有新的权限申请\n\n"
                    + "**工作空间**：" + safeText(context.workspaceName()) + "\n"
                    + "**申请人**：" + safeText(task.actorDisplayName()) + "\n"
                    + "**申请权限**：" + accessLevelLabel(task.commentContentMd()) + "\n\n"
                    + "[去审批](" + normalizedBaseUrl + "/settings/members?tab=requests)";
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED.equals(task.notificationType())) {
            if (OUTCOME_APPROVED.equals(task.sourceType())) {
                return "### 权限申请已通过\n\n"
                        + "**工作空间**：" + safeText(context.workspaceName()) + "\n"
                        + "**授予权限**：" + accessLevelLabel(task.commentContentMd()) + "\n"
                        + "**审批人**：" + safeText(task.actorDisplayName()) + "\n\n"
                        + "[进入工作空间](" + normalizedBaseUrl + "/workspaces)";
            }
            String reason = safeText(task.commentContentMd()).trim();
            return "### 权限申请被拒绝\n\n"
                    + "**工作空间**：" + safeText(context.workspaceName()) + "\n"
                    + "**审批人**：" + safeText(task.actorDisplayName())
                    + (reason.isEmpty() ? "" : "\n**原因**：" + reason);
        }
        return "### 需要你处理\n\n"
                + "**工作空间**：" + safeText(context.workspaceName()) + "\n"
                + "**工单**：" + safeText(task.workitemTitle()) + "\n"
                + "**状态**：" + safeText(context.statusName()) + "\n"
                + "**指派人**：" + safeText(task.actorDisplayName()) + "\n\n"
                + "[查看工单](" + workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()) + ")";
    }

    private static String workitemUrl(String baseUrl, long workitemId, long tenantId) {
        return baseUrl + "/workitems/" + workitemId + "?workspaceId=" + tenantId;
    }

    private static String scheduledTaskRunUrl(String baseUrl, long runId, long tenantId) {
        return baseUrl + "/scheduled-task-runs/" + runId + "?workspaceId=" + tenantId;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String accessLevelLabel(String accessLevel) {
        return switch (safeText(accessLevel)) {
            case "READ_ONLY" -> "只读";
            case "READ_WRITE" -> "读写";
            case "ADMIN" -> "管理员";
            default -> safeText(accessLevel);
        };
    }

    private static String commentSnippetBlock(String commentContentMd) {
        String snippet = commentSnippet(commentContentMd);
        if (snippet.isEmpty()) {
            return "\n";
        }
        return "**提及内容**：\n> " + snippet + "\n\n";
    }

    private static String commentSnippet(String commentContentMd) {
        String normalized = safeText(commentContentMd).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= COMMENT_SNIPPET_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, COMMENT_SNIPPET_MAX_LENGTH) + "...";
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
