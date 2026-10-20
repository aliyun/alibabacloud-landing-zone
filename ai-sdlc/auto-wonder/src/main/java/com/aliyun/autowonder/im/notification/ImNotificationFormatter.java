package com.aliyun.autowonder.im.notification;

import org.springframework.stereotype.Component;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.workitem.AssignmentActor;
import java.util.Locale;

@Component
public class ImNotificationFormatter {
    private static final int COMMENT_SNIPPET_MAX_LENGTH = 30;
    private static final String OUTCOME_APPROVED = "APPROVED";

    public String format(ImNotificationMessageContext context, ImNotificationTask task) {
        String normalizedBaseUrl = trimTrailingSlash(context.baseUrl());
        Locale locale = task.deliveryLocale();
        String workspace = safeText(context.workspaceName());
        String actor = safeText(AssignmentActor.recipientDisplayName(task.actorDisplayName(), task.actorNameKind(), locale));
        String title = task.isScheduledTaskRun()
                && WorkitemCommentMentionedEvent.SCHEDULED_RUN_ID_FALLBACK.equals(task.workitemTitleKind())
                ? SystemMessages.render("notification.scheduled_run.title_fallback",
                        new Object[]{String.valueOf(task.workitemId())}, task.workitemTitle(), locale)
                : safeText(task.workitemTitle());
        if (ImNotificationTask.TYPE_APPROVAL_REQUIRED.equals(task.notificationType())
                || ImNotificationTask.TYPE_INPUT_REQUIRED.equals(task.notificationType())) {
            return render("human_input", locale, title, workspace, safeText(task.commentContentMd()),
                    workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()));
        }
        if (ImNotificationTask.TYPE_COMMENT_MENTION.equals(task.notificationType())) {
            boolean run = task.isScheduledTaskRun();
            return render(run ? "mention_run" : "mention", locale, workspace, title, actor,
                    commentSnippetBlock(task.commentContentMd(), locale),
                    run ? scheduledTaskRunUrl(normalizedBaseUrl, task.workitemId(), context.tenantId())
                            : workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()));
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST.equals(task.notificationType())) {
            return render("access_request", locale, workspace, actor, accessLevelLabel(task.commentContentMd(), locale),
                    memberReviewUrl(normalizedBaseUrl, context.tenantId()));
        }
        if (ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED.equals(task.notificationType())) {
            if (OUTCOME_APPROVED.equals(task.sourceType())) {
                return render("access_approved", locale, workspace, accessLevelLabel(task.commentContentMd(), locale), actor,
                        normalizedBaseUrl + "/workspaces");
            }
            String reason = safeText(task.commentContentMd()).trim();
            return reason.isEmpty() ? render("access_rejected", locale, workspace, actor)
                    : render("access_rejected_reason", locale, workspace, actor, reason);
        }
        String status = context.assignedStatusFallback()
                ? SystemMessages.render("im.status.assigned", null, context.statusName(), locale) : safeText(context.statusName());
        return render("assigned", locale, workspace, title, status, actor,
                workitemUrl(normalizedBaseUrl, task.workitemId(), context.tenantId()));
    }

    private static String render(String template, Locale locale, Object... args) {
        return SystemMessages.render("im.markdown." + template, args, "", locale);
    }

    private static String workitemUrl(String baseUrl, long workitemId, long tenantId) {
        return baseUrl + "/workitems/" + workitemId + "?workspaceId=" + tenantId;
    }

    /**
     * {@code tab=requests} selects the pending-approval pane; {@code workspaceId} makes AppLayout
     * switch to the workspace the request belongs to first and is stripped afterwards, keeping
     * {@code tab}. Same convention as the workitem and scheduled-task-run links above.
     */
    private static String memberReviewUrl(String baseUrl, long tenantId) {
        return baseUrl + "/settings/members?tab=requests&workspaceId=" + tenantId;
    }

    private static String scheduledTaskRunUrl(String baseUrl, long runId, long tenantId) {
        return baseUrl + "/scheduled-task-runs/" + runId + "?workspaceId=" + tenantId;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String accessLevelLabel(String accessLevel, Locale locale) {
        return switch (safeText(accessLevel)) {
            case "READ_ONLY" -> SystemMessages.render("notification.access.read_only", null, "只读", locale);
            case "READ_WRITE" -> SystemMessages.render("notification.access.read_write", null, "读写", locale);
            case "ADMIN" -> SystemMessages.render("notification.access.admin", null, "管理员", locale);
            default -> safeText(accessLevel);
        };
    }

    private static String commentSnippetBlock(String commentContentMd, Locale locale) {
        String snippet = commentSnippet(commentContentMd);
        if (snippet.isEmpty()) {
            return "\n";
        }
        return render("snippet", locale, snippet);
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
