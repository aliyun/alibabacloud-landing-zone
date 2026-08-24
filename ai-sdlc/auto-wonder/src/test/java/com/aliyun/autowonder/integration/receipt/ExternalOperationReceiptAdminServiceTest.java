package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalOperationReceiptAdminServiceTest {

    private IntegrationOutboxDao outboxDao;
    private AuditLogService auditLogService;
    private ExternalOperationReceiptAdminService service;

    @BeforeEach
    void setUp() {
        outboxDao = mock(IntegrationOutboxDao.class);
        auditLogService = mock(AuditLogService.class);
        service = new ExternalOperationReceiptAdminService(outboxDao, auditLogService);
    }

    @Test
    void manualRetryAdvancesLockVersionResetsRetryBudgetAndRequiresAudit() {
        IntegrationOutboxDO receipt = terminalFailure();
        when(outboxDao.findById(11L)).thenReturn(receipt);
        when(outboxDao.manualRetry(11L, 7L, 5L)).thenReturn(1);

        service.manualRetry(11L, 7L, 9L, " fixed permission ");

        InOrder order = inOrder(outboxDao, auditLogService);
        order.verify(outboxDao).findById(11L);
        order.verify(outboxDao).manualRetry(11L, 7L, 5L);
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        order.verify(auditLogService).recordRequired(captor.capture());
        AuditLogRecord audit = captor.getValue();
        assertAudit(audit, ExternalOperationReceiptAdminService.ACTION_MANUAL_RETRY);
        assertEquals("fixed permission", audit.getDetail().get("reason"));
        assertEquals(4, audit.getDetail().get("previousRetryCount"));
        assertEquals(0, audit.getDetail().get("retryCountResetTo"));
    }

    @Test
    void manualConfirmSucceededRequiresReasonAndAudit() {
        IntegrationOutboxDO receipt = terminalFailure();
        when(outboxDao.findById(11L)).thenReturn(receipt);
        when(outboxDao.manualConfirmSucceeded(11L, 7L, 5L)).thenReturn(1);

        service.manualConfirmSucceeded(11L, 7L, 9L, "verified in Aone");

        verify(outboxDao).manualConfirmSucceeded(11L, 7L, 5L);
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertAudit(captor.getValue(),
                ExternalOperationReceiptAdminService.ACTION_MANUAL_CONFIRM_SUCCEEDED);
        assertEquals("verified in Aone", captor.getValue().getDetail().get("reason"));
    }

    @Test
    void rejectsCrossTenantReceiptAsNotFound() {
        IntegrationOutboxDO receipt = terminalFailure();
        receipt.setTenantId(8L);
        when(outboxDao.findById(11L)).thenReturn(receipt);

        assertCode("10404", () -> service.manualRetry(11L, 7L, 9L, "retry"));

        verify(outboxDao, never()).manualRetry(any(), any(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void rejectsRowsThatAreNotTerminalFailures() {
        IntegrationOutboxDO pending = terminalFailure();
        pending.setStatus("PENDING");
        when(outboxDao.findById(11L)).thenReturn(pending);
        assertCode("10409", () -> service.manualRetry(11L, 7L, 9L, "retry"));

        IntegrationOutboxDO autoRetrying = terminalFailure();
        autoRetrying.setNextRetryAt(new Date());
        when(outboxDao.findById(11L)).thenReturn(autoRetrying);
        assertCode("10409", () -> service.manualRetry(11L, 7L, 9L, "retry"));

        verify(outboxDao, never()).manualRetry(any(), any(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void unknownResultCanBeResolvedManually() {
        IntegrationOutboxDO unknown = terminalFailure();
        unknown.setStatus("UNKNOWN");
        when(outboxDao.findById(11L)).thenReturn(unknown);
        when(outboxDao.manualRetry(11L, 7L, 5L)).thenReturn(1);

        service.manualRetry(11L, 7L, 9L, "checked Aone and safe to replay");

        verify(outboxDao).manualRetry(11L, 7L, 5L);
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertEquals("UNKNOWN", captor.getValue().getDetail().get("previousStatus"));
    }

    @Test
    void compareAndSetLossConflictsWithoutAudit() {
        when(outboxDao.findById(11L)).thenReturn(terminalFailure());

        assertCode("10409", () -> service.manualRetry(11L, 7L, 9L, "retry"));

        verify(outboxDao).manualRetry(any(), any(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void validatesReasonBeforeMutation() {
        assertCode("10001", () -> service.manualRetry(11L, 7L, 9L, " "));
        assertCode("10001", () -> service.manualConfirmSucceeded(11L, 7L, 9L, null));

        verifyNoInteractions(outboxDao, auditLogService);
    }

    @Test
    void requiredAuditFailurePropagatesForTransactionalRollback() throws Exception {
        when(outboxDao.findById(11L)).thenReturn(terminalFailure());
        when(outboxDao.manualRetry(any(), any(), anyLong())).thenReturn(1);
        IllegalStateException failure = new IllegalStateException("audit unavailable");
        doThrow(failure).when(auditLogService).recordRequired(any(AuditLogRecord.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.manualRetry(11L, 7L, 9L, "retry"));

        assertSame(failure, thrown);
        assertNotNull(ExternalOperationReceiptAdminService.class
                .getDeclaredMethod("manualRetry", long.class, long.class, long.class, String.class)
                .getAnnotation(Transactional.class));
        assertNotNull(ExternalOperationReceiptAdminService.class
                .getDeclaredMethod("manualConfirmSucceeded", long.class, long.class, long.class, String.class)
                .getAnnotation(Transactional.class));
    }

    private IntegrationOutboxDO terminalFailure() {
        IntegrationOutboxDO receipt = new IntegrationOutboxDO();
        receipt.setId(11L);
        receipt.setTenantId(7L);
        receipt.setProvider("AONE");
        receipt.setEventType("COMMENT_CREATE");
        receipt.setOperationKey("aone.comment:stable");
        receipt.setLockVersion(5L);
        receipt.setStatus("FAILED");
        receipt.setRetryCount(4);
        receipt.setNextRetryAt(null);
        return receipt;
    }

    private void assertAudit(AuditLogRecord audit, String action) {
        assertEquals(7L, audit.getTenantId());
        assertEquals(9L, audit.getActorId());
        assertEquals("HUMAN", audit.getActorType());
        assertEquals("INTEGRATION", audit.getModule());
        assertEquals(action, audit.getAction());
        assertEquals(action, audit.getEventType());
        assertEquals("EXTERNAL_OPERATION_RECEIPT", audit.getTargetType());
        assertEquals(11L, audit.getTargetId());
        assertEquals("MANUAL", audit.getTriggerType());
        assertEquals("ADMIN_API", audit.getTriggerSource());
        assertEquals("FAILED", audit.getDetail().get("previousStatus"));
    }

    private void assertCode(String expected, Runnable action) {
        BizException error = assertThrows(BizException.class, action::run);
        assertEquals(expected, error.getCode());
    }
}
