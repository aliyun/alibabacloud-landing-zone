package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class InAppCommentMentionedListener {

    private static final Logger log = LoggerFactory.getLogger(InAppCommentMentionedListener.class);
    private static final int MAX_CONTENT_LENGTH = 100;

    private final NotifyService notifyService;

    public InAppCommentMentionedListener(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onMentioned(WorkitemCommentMentionedEvent event) {
        try {
            String summary = truncate(event.commentContentMd(), MAX_CONTENT_LENGTH);
            String content = event.actorDisplayName() + " 在「" + event.workitemTitle() + "」@了你：" + summary;

            NotifyEvent notifyEvent = new NotifyEvent();
            notifyEvent.setTenantId(event.tenantId());
            notifyEvent.setType("COMMENT_MENTION");
            notifyEvent.setTitle("有人在评论中@了你");
            notifyEvent.setContent(content);
            notifyEvent.setLink("/workitems/" + event.workitemId());
            notifyEvent.setRefType("WORKITEM");
            notifyEvent.setRefId(event.workitemId());
            notifyEvent.setRecipientIds(List.of(event.recipientUserId()));

            notifyService.notify(notifyEvent);
            log.info("in-app notification sent for comment mention tenantId={} workitemId={} recipient={}",
                    event.tenantId(), event.workitemId(), event.recipientUserId());
        } catch (Exception e) {
            log.error("failed to send in-app notification for comment mention tenantId={} workitemId={} recipient={}",
                    event.tenantId(), event.workitemId(), event.recipientUserId(), e);
        }
    }

    static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
