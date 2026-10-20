package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.workitem.WorkitemDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 工单删除提交后再清理澄清附件；清理失败只留日志，不回滚已完成的工单删除。 */
@Component
public class ClarificationAttachmentCleanupListener {

    private static final Logger log =
            LoggerFactory.getLogger(ClarificationAttachmentCleanupListener.class);

    private final ClarificationAttachmentService attachmentService;

    public ClarificationAttachmentCleanupListener(
            ClarificationAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkitemDeleted(WorkitemDeletedEvent event) {
        try {
            attachmentService.deleteForWorkitem(event.tenantId(), event.workitemId());
        } catch (RuntimeException e) {
            log.warn("clarification attachment cleanup after workitem delete failed workitemId={}: {}",
                    event.workitemId(), e.getMessage());
        }
    }
}
