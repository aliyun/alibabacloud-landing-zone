package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ExternalOperationReceiptService {

    private static final String SUPPORTED_EVENT_TYPE = "COMMENT_CREATE";

    private final IntegrationOutboxDao outboxDao;

    public ExternalOperationReceiptService(IntegrationOutboxDao outboxDao) {
        this.outboxDao = outboxDao;
    }

    public ReceiptResult begin(BeginRequest request) {
        validateRequest(request);
        String connector = request.connector().trim().toUpperCase(Locale.ROOT);
        String payload = ExternalOperationSanitizer.sanitizeJson(request.payloadJson());

        IntegrationOutboxDO existing = outboxDao.findByOperation(
                request.tenantId(), connector, request.bindingId(), request.operationKey());
        if (existing != null) {
            return existing(existing, payload);
        }

        IntegrationOutboxDO receipt = new IntegrationOutboxDO();
        receipt.setTenantId(request.tenantId());
        receipt.setProvider(connector);
        receipt.setBindingId(request.bindingId());
        receipt.setWorkitemId(request.workitemId());
        receipt.setEventType(request.eventType());
        receipt.setPayloadJson(payload);
        receipt.setOperationKey(request.operationKey());
        receipt.setLockVersion(0L);
        receipt.setStatus("PENDING");
        receipt.setRetryCount(0);
        try {
            outboxDao.insert(receipt);
            return new ReceiptResult(receipt, true);
        } catch (DuplicateKeyException race) {
            IntegrationOutboxDO winner = outboxDao.findByOperation(
                    request.tenantId(), connector, request.bindingId(), request.operationKey());
            if (winner == null) {
                throw race;
            }
            return existing(winner, payload);
        }
    }

    private ReceiptResult existing(IntegrationOutboxDO receipt, String payload) {
        if (!ExternalOperationDigests.payloadDigest(payload)
                .equals(ExternalOperationDigests.payloadDigest(receipt.getPayloadJson()))) {
            throw new ExternalOperationReceiptConflictException(receipt.getOperationKey());
        }
        return new ReceiptResult(receipt, false);
    }

    private void validateRequest(BeginRequest request) {
        if (request == null || request.tenantId() <= 0 || request.bindingId() <= 0
                || request.workitemId() <= 0 || isBlank(request.connector())
                || isBlank(request.eventType()) || isBlank(request.operationKey())
                || request.operationKey().length() > 191) {
            throw new IllegalArgumentException("external operation request is incomplete");
        }
        if (!SUPPORTED_EVENT_TYPE.equals(request.eventType())) {
            throw new IllegalArgumentException("only external comment receipts are supported");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record BeginRequest(long tenantId, String connector, long bindingId, long workitemId,
                               String eventType, String operationKey, String payloadJson) {
    }

    public record ReceiptResult(IntegrationOutboxDO receipt, boolean created) {
    }
}
