package com.aliyun.autowonder.squad.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终态收敛必须只作用于仍处于活跃状态的同一 attempt，任何迟到回调都不能覆盖已经 READY 的快照。
 */
class SquadWorkflowFinalizerTest {

    private static final long WS = 100L;
    private static final long GEN = 42L;
    private static final long CONV = 77L;

    private SquadWorkflowDao dao;
    private SquadWorkflowFinalizer finalizer;

    @BeforeEach
    void setUp() {
        dao = mock(SquadWorkflowDao.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        finalizer = new SquadWorkflowFinalizer(dao, manager);
        when(dao.update(any())).thenReturn(1);
    }

    @Test
    void markFailedWritesTheTerminalStateForTheCurrentAttempt() {
        SquadWorkflowDO row = row("GENERATING", "attempt-1");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        assertTrue(finalizer.markFailed(WS, GEN, "attempt-1", "DISPATCH_FAILED", "boom"));

        assertEquals("FAILED", row.getStatus());
        assertEquals("DISPATCH_FAILED", row.getErrorCode());
        assertEquals("boom", row.getErrorDetail());
        assertNotNull(row.getCompletedAt());
        verify(dao).update(row);
    }

    @Test
    void markFailedSkipsInactiveNullAndStaleAttempts() {
        SquadWorkflowDO ready = row("READY", "attempt-1");
        when(dao.lockById(WS, GEN)).thenReturn(ready);
        assertFalse(finalizer.markFailed(WS, GEN, "attempt-1", "X", "detail"));

        SquadWorkflowDO active = row("REPAIRING", "attempt-1");
        active.setDeadlineAt(new java.util.Date(0));
        when(dao.lockById(WS, GEN)).thenReturn(active);
        assertFalse(finalizer.markFailed(WS, GEN, "attempt-2", "X", "detail"));

        when(dao.lockById(WS, GEN)).thenReturn(null);
        assertFalse(finalizer.markFailed(WS, GEN, "attempt-1", "X", "detail"));

        verify(dao, never()).update(any());
    }

    @Test
    void markFailedAcceptsANullAttemptAndBoundsTheDetail() {
        SquadWorkflowDO row = row("GENERATING", "attempt-1");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        assertTrue(finalizer.markFailed(WS, GEN, null, "X", "d".repeat(1200)));

        assertEquals(1000, row.getErrorDetail().length());
    }

    @Test
    void markFailedReportsAFailedUpdate() {
        when(dao.lockById(WS, GEN)).thenReturn(row("GENERATING", "attempt-1"));
        when(dao.update(any())).thenReturn(0);

        assertFalse(finalizer.markFailed(WS, GEN, "attempt-1", "X", "detail"));
    }

    @Test
    void attachConversationBindsTheRowOnlyForTheLiveAttempt() {
        SquadWorkflowDO row = row("GENERATING", "attempt-1");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        assertTrue(finalizer.attachConversation(WS, GEN, "attempt-1", CONV, 88L));

        assertEquals(CONV, row.getConversationId());
        assertEquals(88L, row.getChiefVersionId());
        verify(dao).update(row);

        SquadWorkflowDO stale = row("GENERATING", "attempt-1");
        when(dao.lockById(WS, GEN)).thenReturn(stale);
        assertFalse(finalizer.attachConversation(WS, GEN, "attempt-2", CONV, 88L));
        assertNull(stale.getConversationId());

        SquadWorkflowDO done = row("READY", "attempt-1");
        when(dao.lockById(WS, GEN)).thenReturn(done);
        assertFalse(finalizer.attachConversation(WS, GEN, "attempt-1", CONV, 88L));

        when(dao.lockById(WS, GEN)).thenReturn(null);
        assertFalse(finalizer.attachConversation(WS, GEN, "attempt-1", CONV, 88L));
    }

    @Test
    void attachConversationReportsAFailedUpdate() {
        when(dao.lockById(WS, GEN)).thenReturn(row("GENERATING", "attempt-1"));
        when(dao.update(any())).thenReturn(0);

        assertFalse(finalizer.attachConversation(WS, GEN, "attempt-1", CONV, 88L));
    }

    @Test
    void conversationTerminalFailsAnActiveSnapshot() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(found);

        finalizer.conversationTerminal(WS, CONV);

        assertEquals("FAILED", found.getStatus());
        assertEquals(WorkflowErrorMessages.NO_VALID_RESULT, found.getErrorCode());
        assertNotNull(found.getCompletedAt());
        verify(dao).update(found);
    }

    @Test
    void executorFailureRetainsItsDiagnosticAndDoesNotMasqueradeAsMissingOutput() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(found);
        finalizer.conversationTerminal(WS, CONV, "FAILED", "qoder preflight: dead owner pid 38553");
        assertEquals("EXECUTION_FAILED", found.getErrorCode());
        assertEquals("qoder preflight: dead owner pid 38553", found.getErrorDetail());
    }

    @Test
    void oversizedUnicodeDiagnosticIsBoundedWithoutSplittingSurrogatePairs() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(found);
        finalizer.conversationTerminal(WS, CONV, "FAILED", "故障😀".repeat(1200));
        String detail = found.getErrorDetail();
        assertEquals(1000, detail.codePointCount(0, detail.length()));
        assertTrue(detail.endsWith("…"));
        assertFalse(detail.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF));
    }

    @Test
    void canceledExecutionHasItsOwnFailureCodeAndKeepsTheDiagnostic() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(found);
        finalizer.conversationTerminal(WS, CONV, "CANCELED", "provider process canceled");
        assertEquals("EXECUTION_CANCELED", found.getErrorCode());
        assertEquals("provider process canceled", found.getErrorDetail());
    }

    @Test
    void truncationInsideAQuotedCredentialDoesNotExposeItsPrefix() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(found);
        String raw = "diagnostic ".repeat(88) + " password=\"short-secret-and-more" + "x".repeat(200) + "\"";
        finalizer.conversationTerminal(WS, CONV, "FAILED", raw);
        assertTrue(found.getErrorDetail().contains("short-secret"));
        String display = WorkflowErrorMessages.message(found.getErrorCode(), found.getErrorDetail());
        assertFalse(display.contains("short-secret"));
    }

    @Test
    void normalCompletionWithoutSubmissionRemainsNoValidResultEvenWithDiagnostic() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(found);
        finalizer.conversationTerminal(WS, CONV, "SUCCESS", "unused");
        assertEquals("NO_VALID_RESULT", found.getErrorCode());
    }

    @Test
    void lateExecutorErrorCannotReplaceReadySnapshotOrNewConversation() {
        SquadWorkflowDO found = row("GENERATING", "attempt-1");
        found.setConversationId(CONV);
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        SquadWorkflowDO ready = row("READY", "attempt-1");
        ready.setConversationId(CONV);
        when(dao.lockById(WS, GEN)).thenReturn(ready);
        finalizer.conversationTerminal(WS, CONV, "FAILED", "late error");
        assertEquals("READY", ready.getStatus());
        assertNull(ready.getErrorDetail());
        SquadWorkflowDO newer = row("GENERATING", "attempt-2");
        newer.setConversationId(CONV + 1);
        when(dao.lockById(WS, GEN)).thenReturn(newer);
        finalizer.conversationTerminal(WS, CONV, "FAILED", "old error");
        assertEquals("GENERATING", newer.getStatus());
        verify(dao, never()).update(any());
    }

    @Test
    void conversationTerminalNeverTouchesReadyOrMissingRows() {
        when(dao.findByConversation(WS, CONV)).thenReturn(null);
        finalizer.conversationTerminal(WS, CONV);
        verify(dao, never()).lockById(anyLong(), anyLong());

        SquadWorkflowDO found = row("REPAIRING", "attempt-1");
        when(dao.findByConversation(WS, CONV)).thenReturn(found);
        when(dao.lockById(WS, GEN)).thenReturn(row("READY", "attempt-1"));
        finalizer.conversationTerminal(WS, CONV);
        verify(dao, never()).update(any());

        when(dao.lockById(WS, GEN)).thenReturn(null);
        finalizer.conversationTerminal(WS, CONV);
        verify(dao, never()).update(any());
    }

    @Test
    void markTimedOutOnlyFailsActiveRows() {
        SquadWorkflowDO active = row("REPAIRING", "attempt-1");
        active.setDeadlineAt(new java.util.Date(0));
        when(dao.lockById(WS, GEN)).thenReturn(active);

        finalizer.markTimedOut(WS, GEN);

        assertEquals("FAILED", active.getStatus());
        assertEquals(WorkflowErrorMessages.DEADLINE_EXCEEDED, active.getErrorCode());
        verify(dao).update(active);

        when(dao.lockById(WS, GEN)).thenReturn(row("READY", "attempt-1"));
        finalizer.markTimedOut(WS, GEN);
        when(dao.lockById(WS, GEN)).thenReturn(null);
        finalizer.markTimedOut(WS, GEN);
        verify(dao).update(active);
    }

    @Test
    void expiredScanCannotTimeOutARenewedAttempt() {
        SquadWorkflowDO renewed = row("GENERATING", "new-attempt");
        renewed.setDeadlineAt(new java.util.Date(System.currentTimeMillis() + 1200000));
        when(dao.lockById(WS, GEN)).thenReturn(renewed);
        finalizer.markTimedOut(WS, GEN);
        assertEquals("GENERATING", renewed.getStatus());
        verify(dao, never()).update(any());
    }

    @Test
    void oldConversationCallbackCannotFailANewAttempt() {
        SquadWorkflowDO old = row("GENERATING", "old-attempt");
        old.setConversationId(CONV);
        SquadWorkflowDO renewed = row("GENERATING", "new-attempt");
        renewed.setConversationId(CONV + 1);
        when(dao.findByConversation(WS, CONV)).thenReturn(old);
        when(dao.lockById(WS, GEN)).thenReturn(renewed);
        finalizer.conversationTerminal(WS, CONV);
        assertEquals("GENERATING", renewed.getStatus());
        verify(dao, never()).update(any());
    }

    @Test
    void isActiveOnlyAcceptsTheTwoAnalysingStates() {
        assertFalse(SquadWorkflowFinalizer.isActive(null));
        assertTrue(SquadWorkflowFinalizer.isActive(row("GENERATING", "a")));
        assertTrue(SquadWorkflowFinalizer.isActive(row("REPAIRING", "a")));
        assertFalse(SquadWorkflowFinalizer.isActive(row("READY", "a")));
        assertFalse(SquadWorkflowFinalizer.isActive(row("FAILED", "a")));
    }

    private SquadWorkflowDO row(String status, String attemptId) {
        SquadWorkflowDO row = new SquadWorkflowDO();
        row.setId(GEN);
        row.setWorkspaceId(WS);
        row.setSquadId(5L);
        row.setStatus(status);
        row.setAttemptId(attemptId);
        return row;
    }
}
