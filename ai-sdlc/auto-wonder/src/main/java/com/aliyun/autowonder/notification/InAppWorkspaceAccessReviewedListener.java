package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class InAppWorkspaceAccessReviewedListener {

    private static final Logger log = LoggerFactory.getLogger(InAppWorkspaceAccessReviewedListener.class);
    private static final String TYPE = "WORKSPACE_ACCESS_REVIEWED";
    private static final String REF_TYPE = "WORKSPACE_ACCESS_REQUEST";
    private static final String OUTCOME_APPROVED = "APPROVED";
    private static final String WORKSPACE_LINK = "/workspaces";

    private final NotifyService notifyService;

    public InAppWorkspaceAccessReviewedListener(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceAccessReviewed(WorkspaceAccessReviewedEvent event) {
        try {
            boolean approved = OUTCOME_APPROVED.equals(event.outcome());
            String workspace = WorkspaceAccessNotifyText.workspaceLabel(event.workspaceName());
            String reviewer = WorkspaceAccessNotifyText.safe(event.reviewerDisplayName());

            NotifyEvent notifyEvent = new NotifyEvent();
            notifyEvent.setTenantId(event.tenantId());
            notifyEvent.setType(TYPE);
            if (approved) {
                notifyEvent.setTitle("权限申请已通过");
                notifyEvent.setContent("你加入「" + workspace + "」的申请已通过，授予权限："
                        + WorkspaceAccessNotifyText.accessLevelLabel(event.requestedLevel())
                        + "，审批人：" + reviewer);
            } else {
                String reason = WorkspaceAccessNotifyText.safe(event.rejectReason()).trim();
                notifyEvent.setTitle("权限申请被拒绝");
                notifyEvent.setContent("你加入「" + workspace + "」的申请被拒绝，审批人：" + reviewer
                        + (reason.isEmpty() ? "" : "，原因：" + reason));
            }
            notifyEvent.setLink(WORKSPACE_LINK);
            notifyEvent.setRefType(REF_TYPE);
            notifyEvent.setRefId(event.requestId());
            notifyEvent.setRecipientIds(List.of(event.requesterId()));

            notifyService.notify(notifyEvent);
            log.info("in-app notification sent for access review tenantId={} requestId={} recipient={} outcome={}",
                    event.tenantId(), event.requestId(), event.requesterId(), event.outcome());
        } catch (Exception e) {
            log.error("failed to send in-app notification for access review tenantId={} requestId={} recipient={}",
                    event.tenantId(), event.requestId(), event.requesterId(), e);
        }
    }
}
