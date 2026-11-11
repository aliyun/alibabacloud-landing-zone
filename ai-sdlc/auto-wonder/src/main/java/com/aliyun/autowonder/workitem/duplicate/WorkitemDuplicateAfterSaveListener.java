package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.workitem.WorkitemCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 保存成功后的可选意图检测：在事务提交后尽力触发。
 * 独立故障边界——入队失败只记 ERROR，不回滚已提交的工单。
 */
@Component
public class WorkitemDuplicateAfterSaveListener {

    private static final Logger log = LoggerFactory.getLogger(WorkitemDuplicateAfterSaveListener.class);

    private final WorkitemDuplicateService duplicateService;

    public WorkitemDuplicateAfterSaveListener(WorkitemDuplicateService duplicateService) {
        this.duplicateService = duplicateService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(WorkitemCreatedEvent event) {
        enqueue(event.tenantId(), event.userId(), event.workitemId(), event.title());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContentUpdated(WorkitemContentUpdatedEvent event) {
        // 只改正文（标题未变）不重复调用模型，也不产生二次通知。
        if (!event.titleChanged()) {
            return;
        }
        enqueue(event.tenantId(), event.userId(), event.workitemId(), event.title());
    }

    private void enqueue(long tenantId, long userId, long workitemId, String title) {
        try {
            duplicateService.enqueueAfterSave(tenantId, userId, workitemId, title);
        } catch (RuntimeException failure) {
            log.error("duplicate intent enqueue after save failed tenantId={} workitemId={}", tenantId,
                    workitemId, failure);
        }
    }
}
