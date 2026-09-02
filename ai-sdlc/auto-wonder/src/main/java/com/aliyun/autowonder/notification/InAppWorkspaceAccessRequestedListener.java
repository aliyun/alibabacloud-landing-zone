package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

@Component
public class InAppWorkspaceAccessRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(InAppWorkspaceAccessRequestedListener.class);
    private static final String LEVEL_ADMIN = "ADMIN";
    private static final String REF_TYPE = "WORKSPACE_ACCESS_REQUEST";
    private static final String REVIEW_LINK = "/settings/members?tab=requests";

    private final NotifyService notifyService;
    private final WorkspaceMemberDao memberDao;

    public InAppWorkspaceAccessRequestedListener(NotifyService notifyService, WorkspaceMemberDao memberDao) {
        this.notifyService = notifyService;
        this.memberDao = memberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceAccessRequested(WorkspaceAccessRequestedEvent event) {
        try {
            List<Long> admins = new ArrayList<>();
            for (WorkspaceMemberDO member : memberDao.listByTenant(event.tenantId())) {
                if (!LEVEL_ADMIN.equals(member.getAccessLevel()) || member.getUserId() == null) {
                    continue;
                }
                // An admin who is also the requester would only be told about their own request.
                if (member.getUserId() == event.requesterId()) {
                    continue;
                }
                admins.add(member.getUserId());
            }
            if (admins.isEmpty()) {
                log.info("in-app notification skipped no admins for access request tenantId={} requestId={}",
                        event.tenantId(), event.requestId());
                return;
            }

            String content = WorkspaceAccessNotifyText.safe(event.requesterDisplayName())
                    + " 申请加入「" + WorkspaceAccessNotifyText.workspaceLabel(event.workspaceName())
                    + "」，申请权限：" + WorkspaceAccessNotifyText.accessLevelLabel(event.requestedLevel());

            // One notify call per admin: NotifyService.notify loops recipients with no per-recipient
            // guard around notificationDao.insert, so a single failing admin would drop the bell rows
            // of every admin after it. This listener runs AFTER_COMMIT, so there is no retry to save
            // them. The bell is the persistent channel the UI reads, so isolate each recipient here
            // exactly like the DingTalk sibling does.
            for (Long adminUserId : admins) {
                try {
                    notifyService.notify(notifyEvent(event, content, adminUserId));
                } catch (Exception e) {
                    log.error("failed to send in-app notification for access request tenantId={} "
                                    + "requestId={} recipientId={}",
                            event.tenantId(), event.requestId(), adminUserId, e);
                }
            }
            log.info("in-app notification sent for access request tenantId={} requestId={} recipients={}",
                    event.tenantId(), event.requestId(), admins.size());
        } catch (Exception e) {
            log.error("failed to send in-app notification for access request tenantId={} requestId={} requesterId={}",
                    event.tenantId(), event.requestId(), event.requesterId(), e);
        }
    }

    /** A fresh event per recipient: NotifyEvent is mutable, so a shared instance would alias. */
    private static NotifyEvent notifyEvent(WorkspaceAccessRequestedEvent event, String content, long recipientId) {
        NotifyEvent notifyEvent = new NotifyEvent();
        notifyEvent.setTenantId(event.tenantId());
        notifyEvent.setType(REF_TYPE);
        notifyEvent.setTitle("有新的权限申请");
        notifyEvent.setContent(content);
        notifyEvent.setLink(REVIEW_LINK);
        notifyEvent.setRefType(REF_TYPE);
        notifyEvent.setRefId(event.requestId());
        notifyEvent.setRecipientIds(List.of(recipientId));
        return notifyEvent;
    }
}
