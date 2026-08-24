package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ExternalOperationReceiptAdminService {

    static final String ACTION_MANUAL_RETRY = "EXTERNAL_OPERATION_MANUAL_RETRY";
    static final String ACTION_MANUAL_CONFIRM_SUCCEEDED =
            "EXTERNAL_OPERATION_MANUAL_CONFIRM_SUCCEEDED";
    private static final int MAX_REASON_LENGTH = 512;

    private final IntegrationOutboxDao outboxDao;
    private final AuditLogService auditLogService;

    @Autowired
    public ExternalOperationReceiptAdminService(IntegrationOutboxDao outboxDao,
                                                AuditLogService auditLogService) {
        this.outboxDao = outboxDao;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void manualRetry(long receiptId, long tenantId, long operatorId, String reason) {
        String normalizedReason = requiredText(reason, "人工重试原因", MAX_REASON_LENGTH);
        IntegrationOutboxDO receipt = requireManualActionable(receiptId, tenantId);
        if (outboxDao.manualRetry(receiptId, tenantId, lockVersion(receipt)) != 1) {
            throw stateChanged();
        }
        auditLogService.recordRequired(audit(receipt, tenantId, operatorId,
                ACTION_MANUAL_RETRY, normalizedReason)
                .detail("previousRetryCount", receipt.getRetryCount())
                .detail("retryCountResetTo", 0));
    }

    @Transactional
    public void manualConfirmSucceeded(long receiptId, long tenantId, long operatorId,
                                       String reason) {
        String normalizedReason = requiredText(reason, "人工确认原因", MAX_REASON_LENGTH);
        IntegrationOutboxDO receipt = requireManualActionable(receiptId, tenantId);
        if (outboxDao.manualConfirmSucceeded(receiptId, tenantId, lockVersion(receipt)) != 1) {
            throw stateChanged();
        }
        auditLogService.recordRequired(audit(receipt, tenantId, operatorId,
                ACTION_MANUAL_CONFIRM_SUCCEEDED, normalizedReason));
    }

    private IntegrationOutboxDO requireManualActionable(long receiptId, long tenantId) {
        if (receiptId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Receipt ID 必须为正整数");
        }
        IntegrationOutboxDO receipt = outboxDao.findById(receiptId);
        if (receipt == null || !Objects.equals(receipt.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "外部操作回执不存在");
        }
        boolean terminalFailure = "FAILED".equals(receipt.getStatus()) && receipt.getNextRetryAt() == null;
        if (!terminalFailure && !"UNKNOWN".equals(receipt.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "仅可处理结果不明或已终止且不再自动重试的回执");
        }
        return receipt;
    }

    private String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "不能为空");
        }
        String normalized = ExternalOperationSanitizer.sanitizeText(value.trim());
        if (normalized.length() > maxLength) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private BizException stateChanged() {
        return new BizException(ErrorCode.CONFLICT, "回执状态已变化，请刷新后重试");
    }

    private long lockVersion(IntegrationOutboxDO receipt) {
        return receipt.getLockVersion() == null ? 0L : receipt.getLockVersion();
    }

    private AuditLogRecord audit(IntegrationOutboxDO receipt, long tenantId, long operatorId,
                                 String action, String reason) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(operatorId);
        record.setActorType("HUMAN");
        record.setModule("INTEGRATION");
        record.setAction(action);
        record.setTargetType("EXTERNAL_OPERATION_RECEIPT");
        record.setTargetId(receipt.getId());
        record.setTriggerType("MANUAL");
        record.setTriggerSource("ADMIN_API");
        record.setEventType(action);
        return record.detail("reason", reason)
                .detail("provider", receipt.getProvider())
                .detail("receiptEventType", receipt.getEventType())
                .detail("operationKey", receipt.getOperationKey())
                .detail("previousStatus", receipt.getStatus());
    }
}
