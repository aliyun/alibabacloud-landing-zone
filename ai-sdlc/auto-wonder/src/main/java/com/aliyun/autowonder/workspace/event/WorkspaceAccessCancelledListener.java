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

/**
 * Reliable IM producer for cancelled join requests.
 *
 * <p>Reuses the {@code WORKSPACE_ACCESS_REQUEST} preference and type, but marks the task with
 * {@code sourceType=CANCELLED} so the formatter never renders the "new request" copy or a
 * link to a request record that no longer exists. The key prefix is independent from the
 * "requested" producer, so both alerts for the same request can coexist.
 */
@Component
public class WorkspaceAccessCancelledListener {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessCancelledListener.class);
    private static final String LEVEL_ADMIN = "ADMIN";
    private static final String ACTOR_TYPE_USER = "USER";
    private static final String KEY_PREFIX = "WORKSPACE_ACCESS_CANCELLED";

    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImNotificationQueue queue;
    private final WorkspaceMemberDao memberDao;

    public WorkspaceAccessCancelledListener(UserImIdentityService identityService,
                                            PlatformImChannelConfigService channelConfigService,
                                            ImNotificationQueue queue,
                                            WorkspaceMemberDao memberDao) {
        this.identityService = identityService;
        this.channelConfigService = channelConfigService;
        this.queue = queue;
        this.memberDao = memberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceAccessCancelled(WorkspaceAccessCancelledEvent event) {
        String provider = "UNKNOWN";
        try {
            provider = channelConfigService.selectedProvider();
            log.info("IM workspace access cancellation notification published tenantId={} requestId={} "
                            + "requesterId={} provider={}",
                    event.tenantId(), event.requestId(), event.requesterId(), provider);
            if (!channelConfigService.isReady(provider)) {
                log.info("IM workspace access cancellation notification skipped channel not ready tenantId={} "
                                + "requestId={} requesterId={} provider={}",
                        event.tenantId(), event.requestId(), event.requesterId(), provider);
                return;
            }

            List<WorkspaceMemberDO> members = memberDao.listByTenant(event.tenantId());
            for (WorkspaceMemberDO member : members) {
                if (!LEVEL_ADMIN.equals(member.getAccessLevel()) || member.getUserId() == null) {
                    continue;
                }
                long adminUserId = member.getUserId();
                // Same guard as the in-app listener: the requester must not be told about their own cancel.
                if (adminUserId == event.requesterId()) {
                    continue;
                }
                notifyAdmin(event, adminUserId, provider);
            }
        } catch (Exception e) {
            logFailure(event, provider, "failed", e);
        }
    }

    private void notifyAdmin(WorkspaceAccessCancelledEvent event, long adminUserId, String provider) {
        UserImIdentityDO identity = identityService.find(adminUserId, provider);
        if (identity == null || !hasText(identity.getExternalUserId())) {
            log.info("IM workspace access cancellation notification skipped missing identity tenantId={} "
                            + "requestId={} recipientUserId={} provider={}",
                    event.tenantId(), event.requestId(), adminUserId, provider);
            return;
        }

        ImNotificationTask task = new ImNotificationTask(
                KEY_PREFIX + ":" + event.requestId() + ":" + provider + ":" + adminUserId,
                event.requestId(),
                event.tenantId(),
                // Not a workitem notification: the context resolver runs workitemDao.findById(workitemId)
                // with no tenant predicate, so any non-zero value could load an unrelated workitem. 0 matches none.
                0L,
                adminUserId,
                ACTOR_TYPE_USER,
                event.requesterId(),
                event.requesterDisplayName(),
                null,
                null,
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST,
                event.requestedLevel(),
                ImNotificationTask.SOURCE_ACCESS_CANCELLED);
        try {
            queue.enqueue(task);
        } catch (Exception e) {
            logRecipientFailure(event, adminUserId, provider, "enqueue-failed", e);
            return;
        }
        log.info("IM workspace access cancellation notification queued tenantId={} requestId={} "
                        + "recipientUserId={} provider={}",
                event.tenantId(), event.requestId(), adminUserId, provider);
    }

    private static void logFailure(WorkspaceAccessCancelledEvent event, String provider, String reason,
                                   Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM workspace access cancellation notification {} tenantId={} requestId={} requesterId={} provider={}",
                reason, event.tenantId(), event.requestId(), event.requesterId(), provider, safe);
    }

    private static void logRecipientFailure(WorkspaceAccessCancelledEvent event, long recipientUserId,
                                            String provider, String reason, Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM workspace access cancellation notification {} tenantId={} requestId={} "
                        + "recipientUserId={} provider={}",
                reason, event.tenantId(), event.requestId(), recipientUserId, provider, safe);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
