package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.dispatch.DispatchRequestService.HumanRequestCreated;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DispatchRequestNotificationListener {
    private final NotifyService notifications;

    public DispatchRequestNotificationListener(NotifyService notifications) { this.notifications = notifications; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(HumanRequestCreated request) {
        boolean approval = "APPROVAL".equals(request.kind());
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(request.workspaceId());
        event.setType(approval ? "WORKITEM_APPROVAL_REQUIRED" : "WORKITEM_INPUT_REQUIRED");
        event.setTitle((approval ? "待审批：" : "待回答：") + request.stepName());
        event.setTitleKey(approval ? "notification.dispatch_approval.title" : "notification.dispatch_input.title");
        event.setTitleArgs(new Object[]{request.stepName()});
        event.setContent("工单「" + request.workitemTitle() + "」\n步骤「" + request.stepName() + "」\n"
                + (approval ? "本步骤已完成，请检查产物后批准继续，或填写意见要求修改。" : request.message()));
        event.setContentKey(approval ? "notification.dispatch_approval.content" : "notification.dispatch_input.content");
        if (approval) {
            event.setContentArgs(new Object[]{request.workitemTitle(), request.stepName()});
        } else {
            event.setContentArgsResolver(locale -> new Object[]{request.workitemTitle(), request.stepName(),
                    "CONFIRMATION_REQUIRED".equals(request.messageKind())
                            ? SystemMessages.render("dispatch_request.confirmation_required", null, request.message(), locale)
                            : request.message()});
        }
        event.setLink("/workitems/" + request.workitemId() + "?workspaceId=" + request.workspaceId());
        event.setRefType("WORKITEM");
        event.setRefId(request.workitemId());
        event.setRecipientIds(request.recipients());
        notifications.notify(event);
    }
}
