package com.aliyun.autowonder.workspace.event;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class WorkspaceAccessRequestedListener {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessRequestedListener.class);
    private static final String PROVIDER = "DINGTALK";
    private static final String LEVEL_ADMIN = "ADMIN";
    private static final String ACTOR_TYPE_USER = "USER";

    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImNotificationQueue queue;
    private final WorkspaceMemberDao memberDao;

    public WorkspaceAccessRequestedListener(UserImIdentityService identityService,
                                            PlatformImChannelConfigService channelConfigService,
                                            ImNotificationQueue queue,
                                            WorkspaceMemberDao memberDao) {
        this.identityService = identityService;
        this.channelConfigService = channelConfigService;
        this.queue = queue;
        this.memberDao = memberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceAccessRequested(WorkspaceAccessRequestedEvent event) {
        try {
            log.info("IM workspace access request notification published tenantId={} requestId={} "
                            + "requesterId={} provider={}",
                    event.tenantId(), event.requestId(), event.requesterId(), PROVIDER);
            if (!channelConfigService.isReady(PROVIDER)) {
                log.info("IM workspace access request notification skipped channel not ready tenantId={} "
                                + "requestId={} requesterId={} provider={}",
                        event.tenantId(), event.requestId(), event.requesterId(), PROVIDER);
                return;
            }

            List<WorkspaceMemberDO> members = memberDao.listByTenant(event.tenantId());
            for (WorkspaceMemberDO member : members) {
                if (!LEVEL_ADMIN.equals(member.getAccessLevel()) || member.getUserId() == null) {
                    continue;
                }
                long adminUserId = member.getUserId();
                // An admin who is also the requester would only be told about their own request; stale
                // requests can outlive a membership granted through another route, so skip the noise.
                if (adminUserId == event.requesterId()) {
                    continue;
                }
                notifyAdmin(event, adminUserId);
            }
        } catch (Exception e) {
            logFailure(event, "failed", e);
        }
    }

    private void notifyAdmin(WorkspaceAccessRequestedEvent event, long adminUserId) {
        UserImIdentityDO identity = identityService.find(adminUserId, PROVIDER);
        if (identity == null || !hasText(identity.getExternalUserId())) {
            log.info("IM workspace access request notification skipped missing identity tenantId={} "
                            + "requestId={} recipientUserId={} provider={}",
                    event.tenantId(), event.requestId(), adminUserId, PROVIDER);
            return;
        }

        ImNotificationTask task = new ImNotificationTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST + ":" + event.requestId()
                        + ":" + PROVIDER + ":" + adminUserId,
                event.requestId(),
                event.tenantId(),
                // Not a workitem notification: ImNotificationMessageContextResolver unconditionally
                // runs workitemDao.findById(workitemId) with no tenant predicate, so any non-zero
                // value here would load whichever workitem happens to share that id. 0 matches none.
                0L,
                adminUserId,
                ACTOR_TYPE_USER,
                event.requesterId(),
                event.requesterDisplayName(),
                null,
                null,
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST,
                event.requestedLevel(),
                null);
        try {
            queue.enqueue(task);
        } catch (Exception e) {
            logRecipientFailure(event, adminUserId, "enqueue-failed", e);
            return;
        }
        log.info("IM workspace access request notification queued tenantId={} requestId={} "
                        + "recipientUserId={} provider={}",
                event.tenantId(), event.requestId(), adminUserId, PROVIDER);
    }

    private static void logFailure(WorkspaceAccessRequestedEvent event, String reason, Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM workspace access request notification {} tenantId={} requestId={} requesterId={} provider={}",
                reason, event.tenantId(), event.requestId(), event.requesterId(), PROVIDER, safe);
    }

    private static void logRecipientFailure(WorkspaceAccessRequestedEvent event, long recipientUserId,
                                            String reason, Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM workspace access request notification {} tenantId={} requestId={} "
                        + "recipientUserId={} provider={}",
                reason, event.tenantId(), event.requestId(), recipientUserId, PROVIDER, safe);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
