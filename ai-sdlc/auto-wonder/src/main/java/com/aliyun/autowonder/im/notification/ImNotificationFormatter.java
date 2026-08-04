package com.aliyun.autowonder.im.notification;

import org.springframework.stereotype.Component;

@Component
public class ImNotificationFormatter {
    private static final int COMMENT_SNIPPET_MAX_LENGTH = 30;

    public String format(ImNotificationMessageContext context, ImNotificationTask task) {
        String normalizedBaseUrl = trimTrailingSlash(context.baseUrl());
        if (ImNotificationTask.TYPE_COMMENT_MENTION.equals(task.notificationType())) {
            return "### 评论中提到了你\n\n"
                    + "**组织**：" + safeText(context.orgName()) + "\n"
                    + "**工单**：" + safeText(task.workitemTitle()) + "\n"
                    + "**评论人**：" + safeText(task.actorDisplayName()) + "\n"
                    + commentSnippetBlock(task.commentContentMd())
                    + "[查看工单](" + workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()) + ")";
        }
        return "### 需要你处理\n\n"
                + "**组织**：" + safeText(context.orgName()) + "\n"
                + "**工单**：" + safeText(task.workitemTitle()) + "\n"
                + "**状态**：" + safeText(context.statusName()) + "\n"
                + "**指派人**：" + safeText(task.actorDisplayName()) + "\n\n"
                + "[查看工单](" + workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()) + ")";
    }

    private static String workitemUrl(String baseUrl, long workitemId, long tenantId) {
        return baseUrl + "/workitems/" + workitemId + "?orgId=" + tenantId;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
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
