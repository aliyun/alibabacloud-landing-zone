package com.aliyun.autowonder.workspace.event;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.ImNotificationQueue;
import com.aliyun.autowonder.im.notification.ImNotificationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkspaceAccessReviewedListener {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessReviewedListener.class);
    private static final String PROVIDER = "DINGTALK";
    private static final String OUTCOME_APPROVED = "APPROVED";
    private static final String ACTOR_TYPE_USER = "USER";

    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImNotificationQueue queue;

    public WorkspaceAccessReviewedListener(UserImIdentityService identityService,
                                           PlatformImChannelConfigService channelConfigService,
                                           ImNotificationQueue queue) {
        this.identityService = identityService;
        this.channelConfigService = channelConfigService;
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceAccessReviewed(WorkspaceAccessReviewedEvent event) {
        try {
            log.info("IM workspace access reviewed notification published tenantId={} requestId={} "
                            + "recipientUserId={} outcome={} provider={}",
                    event.tenantId(), event.requestId(), event.requesterId(), event.outcome(), PROVIDER);
            if (!channelConfigService.isReady(PROVIDER)) {
                log.info("IM workspace access reviewed notification skipped channel not ready tenantId={} "
                                + "requestId={} recipientUserId={} provider={}",
                        event.tenantId(), event.requestId(), event.requesterId(), PROVIDER);
                return;
            }
            UserImIdentityDO identity = identityService.find(event.requesterId(), PROVIDER);
            if (identity == null || !hasText(identity.getExternalUserId())) {
                log.info("IM workspace access reviewed notification skipped missing identity tenantId={} "
                                + "requestId={} recipientUserId={} provider={}",
                        event.tenantId(), event.requestId(), event.requesterId(), PROVIDER);
                return;
            }

            ImNotificationTask task = new ImNotificationTask(
                    ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED + ":" + event.requestId()
                            + ":" + PROVIDER + ":" + event.requesterId(),
                    event.requestId(),
                    event.tenantId(),
                    // Not a workitem notification: ImNotificationMessageContextResolver unconditionally
                    // runs workitemDao.findById(workitemId) with no tenant predicate, so any non-zero
                    // value here would load whichever workitem happens to share that id. 0 matches none.
                    0L,
                    event.requesterId(),
                    ACTOR_TYPE_USER,
                    event.reviewerId(),
                    event.reviewerDisplayName(),
                    null,
                    null,
                    ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED,
                    OUTCOME_APPROVED.equals(event.outcome()) ? event.requestedLevel() : event.rejectReason(),
                    event.outcome());
            try {
                queue.enqueue(task);
            } catch (Exception e) {
                logFailure(event, "enqueue-failed", e);
                return;
            }
            log.info("IM workspace access reviewed notification queued tenantId={} requestId={} "
                            + "recipientUserId={} outcome={} provider={}",
                    event.tenantId(), event.requestId(), event.requesterId(), event.outcome(), PROVIDER);
        } catch (Exception e) {
            logFailure(event, "failed", e);
        }
    }

    private static void logFailure(WorkspaceAccessReviewedEvent event, String reason, Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM workspace access reviewed notification {} tenantId={} requestId={} "
                        + "recipientUserId={} outcome={} provider={}",
                reason, event.tenantId(), event.requestId(), event.requesterId(), event.outcome(), PROVIDER, safe);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
