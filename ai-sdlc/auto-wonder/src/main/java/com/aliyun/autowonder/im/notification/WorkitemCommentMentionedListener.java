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
public class WorkitemCommentMentionedListener {
    private static final Logger log = LoggerFactory.getLogger(WorkitemCommentMentionedListener.class);
    private static final String PROVIDER = "DINGTALK";

    private final UserImIdentityService identityService;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImNotificationQueue queue;

    public WorkitemCommentMentionedListener(UserImIdentityService identityService,
                                            PlatformImChannelConfigService channelConfigService,
                                            ImNotificationQueue queue) {
        this.identityService = identityService;
        this.channelConfigService = channelConfigService;
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkitemCommentMentioned(WorkitemCommentMentionedEvent event) {
        try {
            log.info("IM comment mention notification published tenantId={} workitemId={} commentId={} "
                            + "recipientUserId={} provider={}",
                    event.tenantId(), event.workitemId(), event.commentId(), event.recipientUserId(), PROVIDER);
            UserImIdentityDO identity = identityService.find(event.recipientUserId(), PROVIDER);
            if (identity == null || !hasText(identity.getExternalUserId())) {
                log.info("IM comment mention notification skipped missing identity tenantId={} workitemId={} "
                                + "commentId={} recipientUserId={} provider={}",
                        event.tenantId(), event.workitemId(), event.commentId(), event.recipientUserId(), PROVIDER);
                return;
            }
            if (!channelConfigService.isReady(PROVIDER)) {
                log.info("IM comment mention notification skipped channel not ready tenantId={} workitemId={} "
                                + "commentId={} recipientUserId={} provider={}",
                        event.tenantId(), event.workitemId(), event.commentId(), event.recipientUserId(), PROVIDER);
                return;
            }

            ImNotificationTask task = new ImNotificationTask(
                    "COMMENT_MENTION:" + event.commentId() + ":" + PROVIDER + ":" + event.recipientUserId(),
                    event.commentId(),
                    event.tenantId(),
                    event.workitemId(),
                    event.recipientUserId(),
                    event.actorType(),
                    event.actorRef(),
                    event.actorDisplayName(),
                    event.requestId(),
                    event.workitemTitle(),
                    ImNotificationTask.TYPE_COMMENT_MENTION,
                    event.commentContentMd(),
                    event.sourceType());
            try {
                queue.enqueue(task);
            } catch (Exception e) {
                logFailure(event, "enqueue-failed", e);
                return;
            }
            log.info("IM comment mention notification queued tenantId={} workitemId={} commentId={} "
                            + "recipientUserId={} provider={}",
                    event.tenantId(), event.workitemId(), event.commentId(), event.recipientUserId(), PROVIDER);
        } catch (Exception e) {
            logFailure(event, "failed", e);
        }
    }

    private static void logFailure(WorkitemCommentMentionedEvent event, String reason, Exception failure) {
        AlreadyLoggedException safe = AlreadyLoggedException.from(failure);
        log.error("IM comment mention notification {} tenantId={} workitemId={} commentId={} "
                        + "recipientUserId={} provider={}",
                reason, event.tenantId(), event.workitemId(), event.commentId(),
                event.recipientUserId(), PROVIDER, safe);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
