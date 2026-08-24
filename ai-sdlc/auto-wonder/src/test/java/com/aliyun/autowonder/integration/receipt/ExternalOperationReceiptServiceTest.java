package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalOperationReceiptServiceTest {

    @Test
    void sameKeyAndPayloadReturnsExistingReceiptWithoutAnotherInsert() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        ExternalOperationReceiptService service = service(dao);
        ExternalOperationReceiptService.BeginRequest request = request("{\"b\":2,\"a\":1}");
        IntegrationOutboxDO existing = receipt("{\"a\":1,\"b\":2}", "SUCCEEDED");
        when(dao.findByOperation(7L, "AONE", 9L, "stable-key")).thenReturn(existing);

        ExternalOperationReceiptService.ReceiptResult result = service.begin(request);

        assertFalse(result.created());
        assertEquals(existing, result.receipt());
        verify(dao, never()).insert(any());
    }

    @Test
    void existingPendingReceiptReturnsTheExistingRowWithoutCreatingAnother() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        ExternalOperationReceiptService service = service(dao);
        when(dao.findByOperation(7L, "AONE", 9L, "stable-key"))
                .thenReturn(receipt("{\"value\":\"same\"}", "PENDING"));

        ExternalOperationReceiptService.ReceiptResult result =
                service.begin(request("{\"value\":\"same\"}"));

        assertFalse(result.created());
        verify(dao, never()).insert(any());
    }

    @Test
    void sameKeyWithDifferentPayloadConflictsBeforeAnyWrite() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        ExternalOperationReceiptService service = service(dao);
        when(dao.findByOperation(7L, "AONE", 9L, "stable-key"))
                .thenReturn(receipt("{\"value\":\"first\"}", "SUCCEEDED"));

        assertThrows(ExternalOperationReceiptConflictException.class,
                () -> service.begin(request("{\"value\":\"second\"}")));

        verify(dao, never()).insert(any());
    }

    @Test
    void duplicateInsertRaceReturnsWinnerOrConflictsDeterministically() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        ExternalOperationReceiptService service = service(dao);
        IntegrationOutboxDO winner = receipt("{\"value\":\"same\"}", "SENDING");
        when(dao.findByOperation(7L, "AONE", 9L, "stable-key"))
                .thenReturn(null, winner);
        doThrow(new DuplicateKeyException("concurrent winner")).when(dao).insert(any());

        ExternalOperationReceiptService.ReceiptResult result =
                service.begin(request("{\"value\":\"same\"}"));

        assertFalse(result.created());
        assertEquals(winner, result.receipt());
    }

    @Test
    void persistedPayloadIsRedactedAndStartsAtLockVersionZero() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        ExternalOperationReceiptService service = service(dao);
        IntegrationOutboxDO[] inserted = new IntegrationOutboxDO[1];
        doAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(99L);
            return 1;
        }).when(dao).insert(any());

        ExternalOperationReceiptService.ReceiptResult result = service.begin(
                request("{\"accessToken\":\"secret-one\",\"reference\":\"safe\"}"));

        assertTrue(result.created());
        assertFalse(inserted[0].getPayloadJson().contains("secret-one"));
        assertTrue(inserted[0].getPayloadJson().contains("[REDACTED]"));
        assertEquals(0L, inserted[0].getLockVersion());
    }

    @Test
    void rejectsNonCommentOperationsBeforeReadingOrWritingTheOutbox() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        ExternalOperationReceiptService service = service(dao);
        ExternalOperationReceiptService.BeginRequest request =
                new ExternalOperationReceiptService.BeginRequest(
                        7L, "AONE", 9L, 11L, "STATUS_UPDATE", "stable-key", "{}");

        assertThrows(IllegalArgumentException.class, () -> service.begin(request));

        verify(dao, never()).findByOperation(any(), any(), any(), any());
        verify(dao, never()).insert(any());
    }

    private ExternalOperationReceiptService service(IntegrationOutboxDao dao) {
        return new ExternalOperationReceiptService(dao);
    }

    private ExternalOperationReceiptService.BeginRequest request(String payload) {
        return new ExternalOperationReceiptService.BeginRequest(
                7L, " aone ", 9L, 11L, "COMMENT_CREATE", "stable-key", payload);
    }

    private IntegrationOutboxDO receipt(String payload, String status) {
        IntegrationOutboxDO receipt = new IntegrationOutboxDO();
        receipt.setId(1L);
        receipt.setOperationKey("stable-key");
        receipt.setPayloadJson(payload);
        receipt.setStatus(status);
        return receipt;
    }

}
