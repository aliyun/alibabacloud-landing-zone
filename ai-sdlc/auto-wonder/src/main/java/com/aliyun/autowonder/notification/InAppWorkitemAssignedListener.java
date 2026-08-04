package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class InAppWorkitemAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(InAppWorkitemAssignedListener.class);

    private final NotifyService notifyService;

    public InAppWorkitemAssignedListener(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onAssigned(WorkitemHumanAssignedEvent event) {
        try {
            NotifyEvent notifyEvent = new NotifyEvent();
            notifyEvent.setTenantId(event.tenantId());
            notifyEvent.setType("WORKITEM_ASSIGNED");
            notifyEvent.setTitle("有新工单指派给你");
            notifyEvent.setContent(event.workitemTitle());
            notifyEvent.setLink("/workitems/" + event.workitemId());
            notifyEvent.setRefType("WORKITEM");
            notifyEvent.setRefId(event.workitemId());
            notifyEvent.setRecipientIds(List.of(event.recipientUserId()));

            notifyService.notify(notifyEvent);
            log.info("in-app notification sent for workitem assigned tenantId={} workitemId={} recipient={}",
                    event.tenantId(), event.workitemId(), event.recipientUserId());
        } catch (Exception e) {
            log.error("failed to send in-app notification for workitem assigned tenantId={} workitemId={} recipient={}",
                    event.tenantId(), event.workitemId(), event.recipientUserId(), e);
        }
    }
}
