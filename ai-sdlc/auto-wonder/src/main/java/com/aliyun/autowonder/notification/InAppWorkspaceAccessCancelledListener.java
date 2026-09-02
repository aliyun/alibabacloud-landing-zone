package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class InAppWorkspaceAccessCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(InAppWorkspaceAccessCancelledListener.class);
    private static final String LEVEL_ADMIN = "ADMIN";
    private static final String REF_TYPE = "WORKSPACE_ACCESS_REQUEST";
    private static final String REVIEW_LINK = "/settings/members?tab=requests";
    private static final DateTimeFormatter CANCEL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotifyService notifyService;
    private final WorkspaceMemberDao memberDao;

    public InAppWorkspaceAccessCancelledListener(NotifyService notifyService, WorkspaceMemberDao memberDao) {
        this.notifyService = notifyService;
        this.memberDao = memberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceAccessCancelled(WorkspaceAccessCancelledEvent event) {
        try {
            List<Long> admins = new ArrayList<>();
            for (WorkspaceMemberDO member : memberDao.listByTenant(event.tenantId())) {
                if (!LEVEL_ADMIN.equals(member.getAccessLevel()) || member.getUserId() == null) {
                    continue;
                }
                // Same guard as the requested-listener: the requester as their own reviewer
                // only needs the API success feedback, not a bell about their own cancel.
                if (member.getUserId() == event.requesterId()) {
                    continue;
                }
                admins.add(member.getUserId());
            }
            if (admins.isEmpty()) {
                log.info("in-app notification skipped no admins for access cancel tenantId={} requestId={}",
                        event.tenantId(), event.requestId());
                return;
            }

            String content = WorkspaceAccessNotifyText.safe(event.requesterDisplayName())
                    + " 于 " + LocalDateTime.now().format(CANCEL_TIME_FORMAT)
                    + " 撤销了加入「" + WorkspaceAccessNotifyText.workspaceLabel(event.workspaceName())
                    + "」的申请，申请记录将删除";

            // One notify call per admin, same isolation rationale as the requested-listener:
            // NotifyService.notify has no per-recipient guard and this listener runs AFTER_COMMIT.
            for (Long adminUserId : admins) {
                try {
                    notifyService.notify(notifyEvent(event, content, adminUserId));
                } catch (Exception e) {
                    log.error("failed to send in-app notification for access cancel tenantId={} "
                                    + "requestId={} recipientId={}",
                            event.tenantId(), event.requestId(), adminUserId, e);
                }
            }
            log.info("in-app notification sent for access cancel tenantId={} requestId={} recipients={}",
                    event.tenantId(), event.requestId(), admins.size());
        } catch (Exception e) {
            log.error("failed to send in-app notification for access cancel tenantId={} requestId={} requesterId={}",
                    event.tenantId(), event.requestId(), event.requesterId(), e);
        }
    }

    /** A fresh event per recipient: NotifyEvent is mutable, so a shared instance would alias. */
    private static NotifyEvent notifyEvent(WorkspaceAccessCancelledEvent event, String content, long recipientId) {
        NotifyEvent notifyEvent = new NotifyEvent();
        notifyEvent.setTenantId(event.tenantId());
        notifyEvent.setType(REF_TYPE);
        notifyEvent.setTitle("权限申请已撤销");
        notifyEvent.setContent(content);
        notifyEvent.setLink(REVIEW_LINK);
        notifyEvent.setRefType(REF_TYPE);
        notifyEvent.setRefId(event.requestId());
        notifyEvent.setRecipientIds(List.of(recipientId));
        return notifyEvent;
    }
}
