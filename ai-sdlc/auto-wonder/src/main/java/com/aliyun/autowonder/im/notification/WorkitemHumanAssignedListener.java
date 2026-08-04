package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkitemHumanAssignedListener {
    private static final Logger log = LoggerFactory.getLogger(WorkitemHumanAssignedListener.class);
    private static final String PROVIDER = "DINGTALK";

    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImNotificationQueue queue;

    public WorkitemHumanAssignedListener(UserImIdentityService identityService,
                                         PlatformImChannelConfigService channelConfigService,
                                         ImNotificationQueue queue) {
        this.identityService = identityService;
        this.channelConfigService = channelConfigService;
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkitemHumanAssigned(WorkitemHumanAssignedEvent event) {
        try {
            log.info("IM notification published tenantId={} workitemId={} eventId={} recipientUserId={} provider={}",
                    event.tenantId(), event.workitemId(), event.workitemEventId(),
                    event.recipientUserId(), PROVIDER);
            UserImIdentityDO identity = identityService.find(event.recipientUserId(), PROVIDER);
            if (identity == null || !hasText(identity.getExternalUserId())) {
                log.info("IM notification skipped missing identity tenantId={} workitemId={} eventId={} "
                                + "recipientUserId={} provider={}",
                        event.tenantId(), event.workitemId(), event.workitemEventId(),
                        event.recipientUserId(), PROVIDER);
                return;
            }
            if (!channelConfigService.isReady(PROVIDER)) {
                log.info("IM notification skipped channel not ready tenantId={} workitemId={} eventId={} "
                                + "recipientUserId={} provider={}",
                        event.tenantId(), event.workitemId(), event.workitemEventId(),
                        event.recipientUserId(), PROVIDER);
                return;
            }

            ImNotificationTask task = new ImNotificationTask(
                    event.workitemEventId() + ":" + PROVIDER + ":" + event.recipientUserId(),
                    event.workitemEventId(),
                    event.tenantId(),
                    event.workitemId(),
                    event.recipientUserId(),
                    event.actorType(),
                    event.actorRef(),
                    event.actorDisplayName(),
                    event.requestId(),
                    event.workitemTitle());
            try {
                queue.enqueue(task);
            } catch (Exception e) {
                logFailure(event, "enqueue-failed", e);
                return;
            }
            log.info("IM notification queued tenantId={} workitemId={} eventId={} recipientUserId={} provider={}",
                    event.tenantId(), event.workitemId(), event.workitemEventId(),
                    event.recipientUserId(), PROVIDER);
        } catch (Exception e) {
            logFailure(event, "failed", e);
        }
    }

    private static void logFailure(WorkitemHumanAssignedEvent event, String reason, Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM notification {} tenantId={} workitemId={} eventId={} "
                        + "recipientUserId={} provider={}",
                reason, event.tenantId(), event.workitemId(), event.workitemEventId(),
                event.recipientUserId(), PROVIDER, safe);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
