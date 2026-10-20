package com.aliyun.autowonder.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/** 周期清理已上传但 24 小时仍未发送的澄清附件，避免 OSS 与元数据长期滞留。 */
@Component
public class ClarificationAttachmentCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ClarificationAttachmentCleanupTask.class);
    private static final int BATCH_SIZE = 200;

    private final ClarificationAttachmentService attachmentService;

    public ClarificationAttachmentCleanupTask(
            ClarificationAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Scheduled(fixedDelayString =
            "${autowonder.clarification.attachment.cleanup-fixed-delay-ms:3600000}")
    public void cleanupOrphans() {
        Date cutoff = new Date(System.currentTimeMillis()
                - ClarificationAttachmentService.ORPHAN_MAX_AGE_MILLIS);
        try {
            int deleted = attachmentService.cleanupOrphans(cutoff, BATCH_SIZE);
            if (deleted > 0) {
                log.info("clarification attachment orphan cleanup removed={}", deleted);
            }
        } catch (RuntimeException e) {
            // 调度线程不能因单次清理失败退出；异常留给下一轮重试。
            log.warn("clarification attachment orphan cleanup failed: {}", e.getMessage());
        }
    }
}
