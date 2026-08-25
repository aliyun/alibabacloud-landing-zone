package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalOperationRecoveryJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void restartRecoversSendingReceiptWhenReadbackFindsResponseLostSuccess() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        IntegrationOutboxDO receipt = receipt(1L, "AONE", "SENDING", 3L);
        ExternalOperationReadbackHandler handler = handler("AONE",
                ExternalOperationReadbackHandler.ReadbackResult.found());
        when(dao.listRecoveryCandidates(any(), eq(20))).thenReturn(List.of(receipt));
        when(dao.takeoverForRecovery(eq(1L), eq(3L), any())).thenReturn(1);
        when(dao.markSucceeded(any(), anyLong())).thenReturn(1);
        ExternalOperationRecoveryJob job = new ExternalOperationRecoveryJob(
                dao, List.of(handler), CLOCK);

        ExternalOperationRecoveryJob.RecoverySummary summary = job.recover(20);

        assertEquals(1, summary.found());
        verify(dao).markSucceeded(1L, 4L);
        verify(dao, never()).requeueAfterNotFound(any(), anyLong());
    }

    @Test
    void unavailableReadbackKeepsUnknownAndNeverBlindlyRequeues() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        IntegrationOutboxDO receipt = receipt(2L, "AONE", "UNKNOWN", 5L);
        ExternalOperationReadbackHandler handler = handler("AONE",
                ExternalOperationReadbackHandler.ReadbackResult.unavailable("read API unavailable"));
        when(dao.listRecoveryCandidates(any(), eq(20))).thenReturn(List.of(receipt));
        when(dao.takeoverForRecovery(eq(2L), eq(5L), any())).thenReturn(1);
        when(dao.markUnknown(any(), anyLong(), any())).thenReturn(1);
        ExternalOperationRecoveryJob job = new ExternalOperationRecoveryJob(
                dao, List.of(handler), CLOCK);

        ExternalOperationRecoveryJob.RecoverySummary summary = job.recover(20);

        assertEquals(1, summary.unavailable());
        verify(dao).markUnknown(eq(2L), eq(6L), eq("read API unavailable"));
        verify(dao, never()).requeueAfterNotFound(any(), anyLong());
        verify(dao, never()).markFailed(any(), anyLong(), any(Boolean.class), any());
    }

    @Test
    void definitelyNotFoundIsTheOnlyReadbackOutcomeThatRequeues() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        IntegrationOutboxDO receipt = receipt(3L, "AONE", "UNKNOWN", 7L);
        ExternalOperationReadbackHandler handler = handler("AONE",
                ExternalOperationReadbackHandler.ReadbackResult.notFound());
        when(dao.listRecoveryCandidates(any(), eq(20))).thenReturn(List.of(receipt));
        when(dao.takeoverForRecovery(eq(3L), eq(7L), any())).thenReturn(1);
        when(dao.requeueAfterNotFound(any(), anyLong())).thenReturn(1);
        ExternalOperationRecoveryJob job = new ExternalOperationRecoveryJob(
                dao, List.of(handler), CLOCK);

        ExternalOperationRecoveryJob.RecoverySummary summary = job.recover(20);

        assertEquals(1, summary.definitelyNotFound());
        verify(dao).requeueAfterNotFound(3L, 8L);
        verify(dao, never()).markSucceeded(any(), anyLong());
    }

    @Test
    void expiredLockVersionLosesTakeoverAndPerformsNoReadbackOrStateWrite() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        IntegrationOutboxDO receipt = receipt(4L, "AONE", "SENDING", 9L);
        ExternalOperationReadbackHandler handler = mock(ExternalOperationReadbackHandler.class);
        when(dao.listRecoveryCandidates(any(), eq(20))).thenReturn(List.of(receipt));
        when(dao.takeoverForRecovery(eq(4L), eq(9L), any())).thenReturn(0);
        ExternalOperationRecoveryJob job = new ExternalOperationRecoveryJob(
                dao, List.of(handler), CLOCK);

        ExternalOperationRecoveryJob.RecoverySummary summary = job.recover(20);

        assertEquals(new ExternalOperationRecoveryJob.RecoverySummary(0, 0, 0), summary);
        verify(handler, never()).readback(any());
        verify(dao, never()).markUnknown(any(), anyLong(), any());
        verify(dao, never()).markSucceeded(any(), anyLong());
        verify(dao, never()).requeueAfterNotFound(any(), anyLong());
    }

    @Test
    void connectorsRecoverIndependentlyInTheSamePass() {
        IntegrationOutboxDao dao = mock(IntegrationOutboxDao.class);
        IntegrationOutboxDO aone = receipt(5L, "AONE", "UNKNOWN", 10L);
        IntegrationOutboxDO dingTalk = receipt(6L, "DINGTALK", "UNKNOWN", 20L);
        ExternalOperationReadbackHandler aoneHandler = handler("AONE",
                ExternalOperationReadbackHandler.ReadbackResult.found());
        ExternalOperationReadbackHandler dingHandler = handler("DINGTALK",
                ExternalOperationReadbackHandler.ReadbackResult.unavailable("delivery query unavailable"));
        when(dao.listRecoveryCandidates(any(), eq(20))).thenReturn(List.of(aone, dingTalk));
        when(dao.takeoverForRecovery(any(), anyLong(), any())).thenReturn(1);
        when(dao.markSucceeded(any(), anyLong())).thenReturn(1);
        when(dao.markUnknown(any(), anyLong(), any())).thenReturn(1);
        ExternalOperationRecoveryJob job = new ExternalOperationRecoveryJob(
                dao, List.of(aoneHandler, dingHandler), CLOCK);

        ExternalOperationRecoveryJob.RecoverySummary summary = job.recover(20);

        assertEquals(new ExternalOperationRecoveryJob.RecoverySummary(1, 0, 1), summary);
        verify(aoneHandler).readback(aone);
        verify(dingHandler).readback(dingTalk);
        verify(dao).markSucceeded(eq(5L), eq(11L));
        verify(dao).markUnknown(eq(6L), eq(21L), eq("delivery query unavailable"));
    }

    private ExternalOperationReadbackHandler handler(
            String connector, ExternalOperationReadbackHandler.ReadbackResult result) {
        ExternalOperationReadbackHandler handler = mock(ExternalOperationReadbackHandler.class);
        when(handler.connector()).thenReturn(connector);
        when(handler.supports(any())).thenReturn(true);
        when(handler.readback(any())).thenReturn(result);
        return handler;
    }

    private IntegrationOutboxDO receipt(long id, String provider, String status, long lockVersion) {
        IntegrationOutboxDO receipt = new IntegrationOutboxDO();
        receipt.setId(id);
        receipt.setProvider(provider);
        receipt.setStatus(status);
        receipt.setLockVersion(lockVersion);
        receipt.setEventType("TEST_EVENT");
        return receipt;
    }
}
