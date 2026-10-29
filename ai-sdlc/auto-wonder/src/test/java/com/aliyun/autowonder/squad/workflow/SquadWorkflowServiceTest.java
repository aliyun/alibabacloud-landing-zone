package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快照状态机：缓存共享、失败重试、提交预算与跨实例竞态都必须收敛到同一行。
 * 所有写路径先锁行再比较 attemptId，避免旧会话的迟到提交污染新一次尝试。
 */
class SquadWorkflowServiceTest {

    private static final long WS = 100L;
    private static final long SQUAD = 5L;
    private static final long GEN = 42L;
    private static final long CONV = 77L;
    private static final long USER = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INPUT_JSON = "{\"analysisVersion\":\"squad-workflow-v1\","
            + "\"members\":[{\"agentId\":\"11\",\"steps\":[]}],\"sources\":[]}";

    private WorkflowInputAssembler assembler;
    private WorkflowValidator validator;
    private SquadWorkflowDao dao;
    private SquadWorkflowFinalizer finalizer;
    private SquadWorkflowConversationBridge bridge;
    private SquadWorkflowService service;

    @BeforeEach
    void setUp() {
        assembler = mock(WorkflowInputAssembler.class);
        validator = mock(WorkflowValidator.class);
        dao = mock(SquadWorkflowDao.class);
        finalizer = mock(SquadWorkflowFinalizer.class);
        bridge = mock(SquadWorkflowConversationBridge.class);
        service = newService(2, 600, 7, 30);
        when(assembler.assemble(WS, SQUAD)).thenReturn(input());
        when(dao.lockActiveIds(eq(WS), any(), any())).thenReturn(List.of());
        when(dao.update(any())).thenReturn(1);
        when(finalizer.attachConversation(anyLong(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(true);
    }

    private SquadWorkflowService newService(int maxActive, long deadlineSeconds, long failedDays,
            long successDays) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new SquadWorkflowService(assembler, validator, dao, finalizer, bridge, manager,
                maxActive, deadlineSeconds, failedDays, successDays);
    }

    @Test
    void executionFailureExposesOriginalDiagnosticInCurrentAndGenerationViews() {
        SquadWorkflowDO failed = row("FAILED");
        failed.setErrorCode("EXECUTION_FAILED");
        failed.setErrorDetail("qoder minimum version preflight: dead owner pid 38553; /opt/homebrew/bin/qodercli");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION)).thenReturn(failed);
        when(dao.findById(WS, GEN)).thenReturn(failed);
        assertTrue(service.getCurrent(WS, SQUAD).error().message().contains(failed.getErrorDetail()));
        assertTrue(service.getGeneration(WS, SQUAD, GEN).error().message().contains(failed.getErrorDetail()));
    }

    @Test
    void failureDiagnosticsAreRedactedOnReadWithoutChangingStoredOriginal() {
        SquadWorkflowDO failed = row("FAILED");
        failed.setErrorCode("EXECUTION_FAILED");
        String diagnostic = "qoder startup password=private-password token=private-token owner pid 38553";
        failed.setErrorDetail(diagnostic);
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION)).thenReturn(failed);
        String message = service.getCurrent(WS, SQUAD).error().message();
        assertTrue(message.contains("owner pid 38553"));
        assertFalse(message.contains("private-password"));
        assertFalse(message.contains("private-token"));
        assertEquals(diagnostic, failed.getErrorDetail());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void emptySquadRendersAnEmptyViewInsteadOfFailing() {
        when(assembler.assemble(WS, SQUAD))
                .thenThrow(new BizException(ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD));

        SquadWorkflowView view = service.getCurrent(WS, SQUAD);

        assertEquals(SquadWorkflowView.EMPTY, view.status());
        assertEquals(WorkflowInput.ANALYSIS_VERSION, view.analysisVersion());
        assertNull(view.inputHash());
    }

    @Test
    void absentSnapshotRendersMissingWithTheCurrentHash() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);

        SquadWorkflowView view = service.getCurrent(WS, SQUAD);

        assertEquals(SquadWorkflowView.MISSING, view.status());
        assertEquals(input().inputHash(), view.inputHash());
    }

    @Test
    void unrelatedAssemblyFailuresStillPropagate() {
        when(assembler.assemble(WS, SQUAD)).thenThrow(new BizException(ErrorCode.SQUAD_NOT_FOUND));

        assertEquals(ErrorCode.SQUAD_NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.getCurrent(WS, SQUAD)).getCode());
    }

    @Test
    void readySnapshotReturnsTheParsedGraph() {
        SquadWorkflowDO row = row("READY");
        row.setGraphJson("{\"schemaVersion\":\"1.0\"}");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(row);

        SquadWorkflowView view = service.getCurrent(WS, SQUAD);

        assertEquals(SquadWorkflowView.READY, view.status());
        assertNotNull(view.graph());
        assertEquals("1.0", view.graph().path("schemaVersion").asText());
        assertNull(view.error());
    }

    @Test
    void unreadableGraphDegradesToNoGraphInsteadOfFailingThePage() {
        SquadWorkflowDO row = row("READY");
        row.setGraphJson("{not-json");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(row);

        assertNull(service.getCurrent(WS, SQUAD).graph());
    }

    @Test
    void failedSnapshotExposesWhetherThePageMayStartANewAttempt() {
        SquadWorkflowDO row = row("FAILED");
        row.setErrorCode(WorkflowErrorMessages.SUBMISSION_LIMIT);
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(row);
        SquadWorkflowView exhausted = service.getCurrent(WS, SQUAD);
        assertEquals(WorkflowErrorMessages.SUBMISSION_LIMIT, exhausted.error().code());
        assertTrue(exhausted.error().retryable());

        row.setErrorCode(WorkflowErrorMessages.DEADLINE_EXCEEDED);
        assertTrue(service.getCurrent(WS, SQUAD).error().retryable());

        row.setErrorCode(WorkflowErrorMessages.INPUT_TOO_LARGE);
        assertFalse(service.getCurrent(WS, SQUAD).error().retryable());

        row.setErrorCode(null);
        SquadWorkflowView fallback = service.getCurrent(WS, SQUAD);
        assertEquals(WorkflowErrorMessages.INTERNAL_ERROR, fallback.error().code());
        assertTrue(fallback.error().retryable());
    }

    @Test
    void generationLookupChecksOwnershipAndSquadScope() {
        when(dao.findById(WS, GEN)).thenReturn(null);
        assertEquals(ErrorCode.SQUAD_WORKFLOW_GENERATION_NOT_FOUND.getCode(),
                assertThrows(BizException.class,
                        () -> service.getGeneration(WS, SQUAD, GEN)).getCode());

        SquadWorkflowDO foreign = row("READY");
        foreign.setSquadId(SQUAD + 1);
        when(dao.findById(WS, GEN)).thenReturn(foreign);
        assertEquals(ErrorCode.SQUAD_WORKFLOW_GENERATION_NOT_FOUND.getCode(),
                assertThrows(BizException.class,
                        () -> service.getGeneration(WS, SQUAD, GEN)).getCode());

        foreign.setSquadId(null);
        assertThrows(BizException.class, () -> service.getGeneration(WS, SQUAD, GEN));
    }

    // ------------------------------------------------------------- generation

    @Test
    void staleExpectedHashReturnsTheFreshViewWithoutDispatching() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);

        SquadWorkflowStaleInputException thrown = assertThrows(SquadWorkflowStaleInputException.class,
                () -> service.ensureGeneration(WS, SQUAD, USER, "stale-hash", false));

        assertEquals(SquadWorkflowView.MISSING, thrown.getCurrent().status());
        assertEquals(input().inputHash(), thrown.getCurrent().inputHash());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void aNewKeyInsertsGeneratingAndDispatchesTheChief() throws Exception {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);
        when(dao.insert(any())).thenAnswer(invocation -> {
            SquadWorkflowDO inserted = invocation.getArgument(0);
            inserted.setId(GEN);
            return 1;
        });
        when(bridge.start(any(), any()))
                .thenReturn(new SquadWorkflowConversationBridge.Attachment(CONV, 88L));
        when(dao.findById(WS, GEN)).thenAnswer(invocation -> insertedRow());

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, input().inputHash(), false);

        assertEquals(SquadWorkflowView.GENERATING, view.status());
        verify(dao).insert(any());
        verify(bridge).start(any(), eq(input()));
        verify(finalizer).attachConversation(eq(WS), eq(GEN), anyString(), eq(CONV), eq(88L));
    }

    @Test
    void anExistingAttemptIsSharedWithoutRedispatch() {
        SquadWorkflowDO existing = row("GENERATING");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(existing);

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, null, false);

        assertEquals("GENERATING", view.status());
        verify(dao, never()).insert(any());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void aFailedAttemptIsOnlyRetriedWhenTheCallerExplicitlyAsks() {
        SquadWorkflowDO failed = row("FAILED");
        failed.setErrorCode(WorkflowErrorMessages.DEADLINE_EXCEEDED);
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(failed);

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, null, false);

        assertEquals("FAILED", view.status());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void aTimedOutGenerationIsRetriedAndRedispatches() {
        SquadWorkflowDO failed = row("FAILED");
        failed.setErrorCode(WorkflowErrorMessages.DEADLINE_EXCEEDED);
        String previousAttempt = failed.getAttemptId();
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(failed);
        when(dao.lockById(WS, GEN)).thenReturn(failed);
        when(bridge.start(any(), any()))
                .thenReturn(new SquadWorkflowConversationBridge.Attachment(CONV, 88L));
        when(dao.findById(WS, GEN)).thenReturn(failed);

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, null, true);

        assertEquals("GENERATING", view.status());
        assertNotEquals(previousAttempt, failed.getAttemptId());
        assertEquals(0, failed.getSubmissionCount());
        assertNull(failed.getErrorCode());
        verify(dao).update(failed);
        verify(bridge).start(eq(failed), eq(input()));
    }

    @Test
    void aRetryThatLostTheRaceSharesTheWinnersRow() {
        SquadWorkflowDO failed = row("FAILED");
        SquadWorkflowDO ready = row("READY");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(failed);
        when(dao.lockById(WS, GEN)).thenReturn(ready);

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, null, true);

        assertEquals("READY", view.status());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void retryLockedRowThatDisappearedReturnsTheStaleRead() {
        SquadWorkflowDO failed = row("FAILED");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(failed);
        when(dao.lockById(WS, GEN)).thenReturn(null);

        assertEquals("FAILED", service.ensureGeneration(WS, SQUAD, USER, null, true).status());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void quotaRejectsTheThirdConcurrentGeneration() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);
        when(dao.lockActiveIds(eq(WS), any(), any())).thenReturn(List.of(1L, 2L));

        BizException thrown = assertThrows(BizException.class,
                () -> service.ensureGeneration(WS, SQUAD, USER, null, false));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_BUSY.getCode(), thrown.getCode());
    }

    @Test
    void duplicateInsertFallsBackToTheWinningRow() {
        SquadWorkflowDO winner = row("GENERATING");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null, winner);
        when(dao.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, null, false);

        assertEquals("GENERATING", view.status());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void duplicateInsertWithoutAWinnerIsAVersionConflict() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);
        when(dao.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT.getCode(),
                assertThrows(BizException.class,
                        () -> service.ensureGeneration(WS, SQUAD, USER, null, false)).getCode());
    }

    @Test
    void deadlockVictimRetriesAndSharesTheWinningRow() {
        SquadWorkflowDO winner = row("GENERATING");
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null, winner);
        when(dao.lockActiveIds(eq(WS), any(), any()))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenReturn(List.of());

        SquadWorkflowView view = service.ensureGeneration(WS, SQUAD, USER, null, false);

        assertEquals("GENERATING", view.status());
        verify(dao, never()).insert(any());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void persistentDeadlocksSurfaceAsAVersionConflict() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);
        when(dao.lockActiveIds(eq(WS), any(), any()))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT.getCode(),
                assertThrows(BizException.class,
                        () -> service.ensureGeneration(WS, SQUAD, USER, null, false)).getCode());
        verify(dao, never()).insert(any());
    }

    @Test
    void chiefUnavailabilityFailsTheSnapshotWithARetryableCode() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);
        when(dao.insert(any())).thenAnswer(invocation -> {
            ((SquadWorkflowDO) invocation.getArgument(0)).setId(GEN);
            return 1;
        });
        when(bridge.start(any(), any()))
                .thenThrow(new BizException(ErrorCode.SQUAD_WORKFLOW_CHIEF_UNAVAILABLE));

        service.ensureGeneration(WS, SQUAD, USER, null, false);

        verify(finalizer).markFailed(eq(WS), eq(GEN), anyString(),
                eq(WorkflowErrorMessages.CHIEF_UNAVAILABLE), anyString());
    }

    @Test
    void anyOtherDispatchFailureFailsTheSnapshotWithDispatchFailed() {
        when(dao.findByKey(WS, SQUAD, input().inputHash(), WorkflowInput.ANALYSIS_VERSION))
                .thenReturn(null);
        when(dao.insert(any())).thenAnswer(invocation -> {
            ((SquadWorkflowDO) invocation.getArgument(0)).setId(GEN);
            return 1;
        });
        when(bridge.start(any(), any())).thenThrow(new IllegalStateException("executor offline"));

        service.ensureGeneration(WS, SQUAD, USER, null, false);

        verify(finalizer).markFailed(eq(WS), eq(GEN), anyString(),
                eq(WorkflowErrorMessages.DISPATCH_FAILED), eq("executor offline"));
    }

    // ------------------------------------------------------------- submission

    @Test
    void submissionRejectsMalformedIdentityArguments() {
        assertEquals(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST.getCode(),
                assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "42",
                        "a", " ", "{}")).getCode());
        assertEquals(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST.getCode(),
                assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "42",
                        "", "s1", "{}")).getCode());
        assertEquals(ErrorCode.SQUAD_WORKFLOW_GENERATION_NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "abc",
                        "a", "s1", "{}")).getCode());
    }

    @Test
    void submissionRequiresTheSnapshotToBelongToThisConversation() {
        SquadWorkflowDO row = row("GENERATING");
        row.setConversationId(null);
        when(dao.lockById(WS, GEN)).thenReturn(row);

        assertEquals(ErrorCode.SQUAD_WORKFLOW_ATTEMPT_MISMATCH.getCode(),
                assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "42",
                        "attempt-1", "s1", "{}")).getCode());

        row.setConversationId(CONV + 1);
        assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "42",
                "attempt-1", "s1", "{}"));
    }

    @Test
    void submissionRequiresTheCurrentAttempt() {
        SquadWorkflowDO row = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        assertEquals(ErrorCode.SQUAD_WORKFLOW_ATTEMPT_MISMATCH.getCode(),
                assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "42",
                        "old-attempt", "s1", "{}")).getCode());
    }

    @Test
    void submissionAfterTerminalStateIsRejectedWithoutTouchingTheRow() {
        SquadWorkflowDO row = row("FAILED");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{}");

        assertFalse(feedback.accepted());
        assertEquals("NOT_AVAILABLE", feedback.errors().get(0).code());
        assertFalse(feedback.retryable());
        assertEquals("42", feedback.generationId());
        assertEquals("attempt-1", feedback.attemptId());
        assertEquals("s1", feedback.submissionId());
        assertEquals(WorkflowValidator.SCHEMA_VERSION, feedback.schemaVersion());
        assertEquals(0, feedback.remainingRepairAttempts());
        assertTrue(feedback.repairInstruction().startsWith("repair-v1"));
        verify(dao, never()).update(any());
    }

    @Test
    void expiredDeadlineFailsTheSnapshotAndRejectsTheSubmission() {
        SquadWorkflowDO row = row("GENERATING");
        row.setDeadlineAt(new Date(System.currentTimeMillis() - 1000));
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{}");

        assertFalse(feedback.accepted());
        assertEquals(WorkflowErrorMessages.DEADLINE_EXCEEDED, feedback.errors().get(0).code());
        assertFalse(feedback.retryable());
        assertEquals("FAILED", row.getStatus());
        verify(dao).update(row);
    }

    @Test
    void reusedSubmissionIdWithDifferentBytesIsAConflict() throws Exception {
        SquadWorkflowDO row = row("GENERATING");
        row.setSubmissionReceiptsJson(receipts(new WorkflowSubmissionReceipt("s1", "other-digest",
                WorkflowFeedback.REJECTED, "2026-10-22T00:00:00Z", List.of(), "bad")));
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1",
                "{\"changed\":true}");

        assertFalse(feedback.accepted());
        assertEquals(WorkflowValidator.ID_DUPLICATE, feedback.errors().get(0).code());
    }

    @Test
    void identicalRetryReplaysTheStoredRejectionWithoutSpendingBudget() throws Exception {
        String payload = "{\"candidate\":1}";
        String digest = WorkflowInputAssembler.sha256Hex(payload);
        SquadWorkflowDO row = row("GENERATING");
        row.setSubmissionReceiptsJson(receipts(new WorkflowSubmissionReceipt("s1", digest,
                WorkflowFeedback.REJECTED, "2026-10-22T00:00:00Z",
                List.of(new WorkflowValidationError("X", "/a", "bad", "good")), "bad")));
        row.setSubmissionCount(1);
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);

        assertFalse(feedback.accepted());
        assertEquals("X", feedback.errors().get(0).code());
        assertTrue(feedback.retryable());
        assertEquals(2, feedback.remainingRepairAttempts());
        assertTrue(feedback.repairInstruction().contains("Remaining submissions: 2"));
        assertEquals(1, row.getSubmissionCount());
        verify(dao, never()).update(any());
    }

    @Test
    void replayingAStoredRejectionUsesThePersistedTruncationFlag() throws Exception {
        String payload = "{\"candidate\":3}";
        String digest = WorkflowInputAssembler.sha256Hex(payload);
        List<WorkflowValidationError> stored = new ArrayList<>();
        for (int i = 0; i < SquadWorkflowService.MAX_STORED_ERRORS; i++) {
            stored.add(new WorkflowValidationError("X", "/workflow/nodes/" + i, "bad", "good"));
        }
        SquadWorkflowDO row = row("GENERATING");
        row.setSubmissionReceiptsJson(receipts(new WorkflowSubmissionReceipt("s1", digest,
                WorkflowFeedback.REJECTED, "2026-10-22T00:00:00Z", stored, "bad")));
        row.setSubmissionCount(1);
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);

        assertFalse(feedback.accepted());
        assertFalse(feedback.errorsTruncated());
        assertEquals(SquadWorkflowService.MAX_STORED_ERRORS, feedback.errors().size());

        row.setSubmissionReceiptsJson(receipts(new WorkflowSubmissionReceipt("s1", digest,
                WorkflowFeedback.REJECTED, "2026-10-22T00:00:00Z", stored.subList(0, 3), "bad",
                true)));
        WorkflowFeedback flagged = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);

        assertTrue(flagged.errorsTruncated());
        assertEquals(3, flagged.errors().size());
    }

    @Test
    void aValidationRejectionPersistsTheTruncationFlagInTheReceipt() {
        List<WorkflowValidationError> errors = new ArrayList<>();
        for (int i = 0; i <= SquadWorkflowService.MAX_STORED_ERRORS; i++) {
            errors.add(new WorkflowValidationError("X", "/workflow/nodes/" + i, "bad", "good"));
        }
        when(validator.validateJson(any(), anyString())).thenReturn(
                new WorkflowFeedback(WorkflowFeedback.REJECTED, "s1", errors, "bad", null)
                        .truncated(true));
        SquadWorkflowDO row = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{}");

        assertTrue(feedback.errorsTruncated());
        assertEquals(SquadWorkflowService.MAX_STORED_ERRORS + 1, feedback.errors().size());
        assertEquals("REPAIRING", row.getStatus());
        assertTrue(row.getSubmissionReceiptsJson().contains("\"errorsTruncated\":true"));
    }

    @Test
    void aRejectionAboveTheStoredCapPersistsTruncationEvenBelowTheValidatorCap() throws Exception {
        List<WorkflowValidationError> errors = new ArrayList<>();
        for (int i = 0; i <= SquadWorkflowService.MAX_STORED_ERRORS; i++) {
            errors.add(new WorkflowValidationError("X", "/workflow/nodes/" + i, "bad", "good"));
        }
        // 21 errors stay below the validator's own 50-error cap, so only the receipt is truncated.
        when(validator.validateJson(any(), anyString())).thenReturn(
                new WorkflowFeedback(WorkflowFeedback.REJECTED, null, errors, "bad", null));
        SquadWorkflowDO row = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(row);
        String payload = "{\"candidate\":5}";

        WorkflowFeedback live = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);

        assertFalse(live.errorsTruncated());
        assertEquals(SquadWorkflowService.MAX_STORED_ERRORS + 1, live.errors().size());
        assertTrue(row.getSubmissionReceiptsJson().contains("\"errorsTruncated\":true"));

        SquadWorkflowDO replayRow = row("REPAIRING");
        replayRow.setSubmissionReceiptsJson(row.getSubmissionReceiptsJson());
        replayRow.setSubmissionCount(1);
        when(dao.lockById(WS, GEN)).thenReturn(replayRow);

        WorkflowFeedback replay = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);

        assertTrue(replay.errorsTruncated());
        assertEquals(SquadWorkflowService.MAX_STORED_ERRORS, replay.errors().size());
        assertEquals("s1", replay.submissionId());
    }

    @Test
    void validatorFeedbackEchoesTheSubmissionIdentityAndAlwaysCarriesARepairInstruction()
            throws Exception {
        when(validator.validateJson(any(), anyString())).thenReturn(
                new WorkflowFeedback(WorkflowFeedback.REJECTED, null,
                        List.of(new WorkflowValidationError(WorkflowValidator.TYPE_MISMATCH, "",
                                "result must be a JSON object", "")), "", null));
        when(dao.lockById(WS, GEN)).thenReturn(row("GENERATING"));

        WorkflowFeedback rejection = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{}");

        // The submission tool declares submissionId and repairInstruction as non-null strings; a
        // null id made strict MCP clients answer -32602 and swallowed the whole repair feedback.
        assertEquals("s1", rejection.submissionId());
        assertEquals("42", rejection.generationId());
        assertEquals("attempt-1", rejection.attemptId());
        assertEquals(WorkflowValidator.SCHEMA_VERSION, rejection.schemaVersion());
        assertNotNull(rejection.repairInstruction());

        when(validator.validateJson(any(), anyString())).thenReturn(new WorkflowFeedback(
                WorkflowFeedback.ACCEPTED, null, List.of(), "",
                MAPPER.readTree("{\"schemaVersion\":\"1.0\"}")));
        SquadWorkflowDO accepting = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(accepting);

        WorkflowFeedback acceptance = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s2", "{}");

        assertTrue(acceptance.accepted());
        assertEquals("s2", acceptance.submissionId());
        assertEquals("42", acceptance.generationId());
        assertEquals("attempt-1", acceptance.attemptId());
        assertNotNull(acceptance.repairInstruction());
        assertTrue(acceptance.repairInstruction().startsWith("repair-v1"));
        assertEquals("READY", accepting.getStatus());
    }

    @Test
    void receiptsStoredBeforeTheTruncationFlagRemainReadable() throws Exception {
        String payload = "{\"candidate\":4}";
        String digest = WorkflowInputAssembler.sha256Hex(payload);
        SquadWorkflowDO row = row("GENERATING");
        row.setSubmissionReceiptsJson("[{\"submissionId\":\"s1\",\"digest\":\"" + digest
                + "\",\"status\":\"REJECTED\",\"submittedAt\":\"2026-10-22T00:00:00Z\","
                + "\"errors\":[],\"summary\":\"bad\"}]");
        row.setSubmissionCount(1);
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);

        assertFalse(feedback.accepted());
        assertFalse(feedback.errorsTruncated());
        assertEquals(1, row.getSubmissionCount());
        verify(dao, never()).update(any());
    }

    @Test
    void readySnapshotReplaysTheAcceptedReceiptOrRejectsNewIds() throws Exception {
        String payload = "{\"candidate\":2}";
        String digest = WorkflowInputAssembler.sha256Hex(payload);
        SquadWorkflowDO row = row("READY");
        row.setGraphJson("{\"schemaVersion\":\"1.0\"}");
        row.setSubmissionReceiptsJson(receipts(new WorkflowSubmissionReceipt("s1", digest,
                WorkflowFeedback.ACCEPTED, "2026-10-22T00:00:00Z", List.of(), "ok")));
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback replay = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", payload);
        assertTrue(replay.accepted());
        assertNotNull(replay.graph());
        assertFalse(replay.retryable());
        assertEquals("42", replay.generationId());

        WorkflowFeedback conflict = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1",
                "{\"different\":true}");
        assertEquals(WorkflowValidator.ID_DUPLICATE, conflict.errors().get(0).code());
        assertFalse(conflict.retryable());

        WorkflowFeedback unknown = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s2", payload);
        assertEquals(WorkflowValidator.ID_DUPLICATE, unknown.errors().get(0).code());
        assertFalse(unknown.retryable());
        assertNull(unknown.graph());
    }

    @Test
    void exhaustedBudgetFailsTheSnapshotBeforeRevalidating() throws Exception {
        SquadWorkflowDO row = row("GENERATING");
        row.setSubmissionReceiptsJson(receipts(
                new WorkflowSubmissionReceipt("s1", "d1", WorkflowFeedback.REJECTED,
                        "2026-10-22T00:00:00Z", List.of(), "bad"),
                new WorkflowSubmissionReceipt("s2", "d2", WorkflowFeedback.REJECTED,
                        "2026-10-22T00:00:01Z", List.of(), "bad"),
                new WorkflowSubmissionReceipt("s3", "d3", WorkflowFeedback.REJECTED,
                        "2026-10-22T00:00:02Z", List.of(), "bad")));
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s4", "{}");

        assertFalse(feedback.accepted());
        assertEquals(WorkflowErrorMessages.SUBMISSION_LIMIT, feedback.errors().get(0).code());
        assertFalse(feedback.retryable());
        assertEquals(0, feedback.remainingRepairAttempts());
        assertEquals("FAILED", row.getStatus());
        verify(validator, never()).validateJson(any(), anyString());
    }

    @Test
    void unreadableStoredInputFailsTheSnapshot() {
        SquadWorkflowDO row = row("GENERATING");
        row.setInputJson("not-json");
        when(dao.lockById(WS, GEN)).thenReturn(row);

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{}");

        assertEquals(WorkflowErrorMessages.INTERNAL_ERROR, feedback.errors().get(0).code());
        assertEquals("FAILED", row.getStatus());
    }

    @Test
    void acceptedSubmissionFreezesTheGraphAndTheReceipt() {
        SquadWorkflowDO row = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(row);
        when(validator.validateJson(any(), anyString())).thenReturn(new WorkflowFeedback(
                WorkflowFeedback.ACCEPTED, null, List.of(), "", null));

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1",
                "{\"accepted\":true}");

        assertTrue(feedback.accepted());
        assertEquals("READY", row.getStatus());
        assertEquals("{\"accepted\":true}", row.getGraphJson());
        assertEquals(1, row.getSubmissionCount());
        assertEquals(0, row.getRepairCount());
        assertNotNull(row.getCompletedAt());
        assertNotNull(row.getSubmissionReceiptsJson());
        verify(dao).update(row);
    }

    @Test
    void rejectedSubmissionMovesToRepairingAndKeepsStructuredErrors() {
        SquadWorkflowDO row = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(row);
        when(validator.validateJson(any(), anyString())).thenReturn(new WorkflowFeedback(
                WorkflowFeedback.REJECTED, null,
                List.of(new WorkflowValidationError(WorkflowValidator.EVIDENCE_QUOTE_NOT_FOUND,
                        "/workflow/edges/0", "quote missing", "verbatim")), "invalid", null));

        WorkflowFeedback feedback = service.acceptSubmission(WS, CONV, "42", "attempt-1", "s1",
                "{\"bad\":true}");

        assertFalse(feedback.accepted());
        assertEquals("REPAIRING", row.getStatus());
        assertEquals(1, row.getRepairCount());
        assertEquals(1, row.getSubmissionCount());
        assertNull(row.getCompletedAt());
    }

    @Test
    void theThirdRejectionExhaustsTheBudgetAndFailsTheSnapshot() throws Exception {
        SquadWorkflowDO row = row("REPAIRING");
        row.setSubmissionReceiptsJson(receipts(
                new WorkflowSubmissionReceipt("s1", "d1", WorkflowFeedback.REJECTED,
                        "2026-10-22T00:00:00Z", List.of(), "bad"),
                new WorkflowSubmissionReceipt("s2", "d2", WorkflowFeedback.REJECTED,
                        "2026-10-22T00:00:01Z", List.of(), "bad")));
        when(dao.lockById(WS, GEN)).thenReturn(row);
        when(validator.validateJson(any(), anyString())).thenReturn(new WorkflowFeedback(
                WorkflowFeedback.REJECTED, null,
                List.of(new WorkflowValidationError("X", "/", "bad", "")), "bad", null));

        service.acceptSubmission(WS, CONV, "42", "attempt-1", "s3", "{\"bad\":true}");

        assertEquals("FAILED", row.getStatus());
        assertEquals(WorkflowErrorMessages.SUBMISSION_LIMIT, row.getErrorCode());
        assertEquals(3, row.getSubmissionCount());
        assertEquals(3, row.getRepairCount());
        assertNotNull(row.getCompletedAt());
    }

    @Test
    void losingTheOptimisticUpdateFailsLoudly() {
        SquadWorkflowDO row = row("GENERATING");
        when(dao.lockById(WS, GEN)).thenReturn(row);
        when(dao.update(any())).thenReturn(0);
        when(validator.validateJson(any(), anyString())).thenReturn(new WorkflowFeedback(
                WorkflowFeedback.ACCEPTED, null, List.of(), "", null));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT.getCode(),
                assertThrows(BizException.class, () -> service.acceptSubmission(WS, CONV, "42",
                        "attempt-1", "s1", "{}")).getCode());
    }

    // ------------------------------------------------------------ recovery

    @Test
    void recoveryTimesOutExpiredRowsAndRedeploysUndispatchedOnes() {
        SquadWorkflowDO expired = row("GENERATING");
        when(dao.listExpired(any(), eq(100))).thenReturn(List.of(expired));

        SquadWorkflowDO orphan = row("GENERATING");
        orphan.setInputJson(INPUT_JSON);
        when(dao.listUndispatched(any(), eq(100))).thenReturn(List.of(orphan));
        when(finalizer.claimDispatch(WS, GEN, orphan.getAttemptId())).thenReturn(true);
        when(bridge.start(any(), any()))
                .thenReturn(new SquadWorkflowConversationBridge.Attachment(CONV, 88L));

        service.recover();

        verify(finalizer).markTimedOut(WS, GEN);
        verify(bridge).start(orphan, WorkflowInputCodec.fromPayload(WS, SQUAD, INPUT_JSON));
        verify(finalizer).attachConversation(eq(WS), eq(GEN), eq(orphan.getAttemptId()),
                eq(CONV), eq(88L));
    }

    @Test
    void recoverySkipsLostClaimsAndUnreadableInput() {
        SquadWorkflowDO lost = row("GENERATING");
        lost.setInputJson(INPUT_JSON);
        SquadWorkflowDO blank = row("GENERATING");
        blank.setAttemptId("attempt-2");
        blank.setInputJson(" ");
        SquadWorkflowDO broken = row("GENERATING");
        broken.setAttemptId("attempt-3");
        broken.setInputJson("not-json");
        when(dao.listUndispatched(any(), eq(100))).thenReturn(List.of(lost, blank, broken));
        when(finalizer.claimDispatch(WS, GEN, lost.getAttemptId())).thenReturn(false);
        when(finalizer.claimDispatch(WS, GEN, blank.getAttemptId())).thenReturn(true);
        when(finalizer.claimDispatch(WS, GEN, broken.getAttemptId())).thenReturn(true);
        when(dao.listExpired(any(), eq(100))).thenReturn(null);

        service.recover();

        verify(finalizer, never()).markTimedOut(anyLong(), anyLong());
        verify(finalizer, never()).markFailed(eq(WS), eq(GEN), eq(lost.getAttemptId()),
                anyString(), anyString());
        verify(finalizer).markFailed(eq(WS), eq(GEN), eq(blank.getAttemptId()),
                eq(WorkflowErrorMessages.INTERNAL_ERROR), anyString());
        verify(finalizer).markFailed(eq(WS), eq(GEN), eq(broken.getAttemptId()),
                eq(WorkflowErrorMessages.INTERNAL_ERROR), anyString());
        verify(bridge, never()).start(any(), any());
    }

    @Test
    void cleanupDeletesFailedAndStaleReadyRows() {
        SquadWorkflowDO failed = row("FAILED");
        failed.setId(41L);
        SquadWorkflowDO ready = row("READY");
        ready.setId(43L);
        ready.setInputHash("stale-hash");
        when(dao.listCleanup(any(), any(), eq(100))).thenReturn(List.of(failed, ready));

        service.cleanupExpired();

        verify(dao).deleteById(failed.getId());
        verify(dao).deleteById(ready.getId());
    }

    @Test
    void cleanupKeepsTheOlderReadyRowThatMatchesTheCurrentAssemblyAfterARollback() {
        SquadWorkflowDO current = row("READY");
        current.setId(41L);
        SquadWorkflowDO stale = row("READY");
        stale.setId(43L);
        stale.setInputHash("hash-b");
        when(dao.listCleanup(any(), any(), eq(100))).thenReturn(List.of(current, stale));

        service.cleanupExpired();

        verify(dao, never()).deleteById(current.getId());
        verify(dao).deleteById(stale.getId());
    }

    @Test
    void cleanupKeepsReadyRowsWhenTheCurrentAssemblyCannotBeRecomputed() {
        SquadWorkflowDO first = row("READY");
        first.setId(41L);
        SquadWorkflowDO second = row("READY");
        second.setId(43L);
        when(dao.listCleanup(any(), any(), eq(100))).thenReturn(List.of(first, second));
        when(assembler.assemble(WS, SQUAD))
                .thenThrow(new BizException(ErrorCode.SQUAD_WORKFLOW_INPUT_TOO_LARGE));

        service.cleanupExpired();

        verify(assembler, times(1)).assemble(WS, SQUAD);
        verify(dao, never()).deleteById(anyLong());
    }

    @Test
    void cleanupKeepsReadyRowsWhenTheAssemblyThrowsAnUnexpectedFailure() {
        SquadWorkflowDO ready = row("READY");
        ready.setId(41L);
        when(dao.listCleanup(any(), any(), eq(100))).thenReturn(List.of(ready));
        when(assembler.assemble(WS, SQUAD)).thenThrow(new IllegalStateException("boom"));

        service.cleanupExpired();

        verify(dao, never()).deleteById(anyLong());
    }

    @Test
    void cleanupDeletesReadyRowsWhoseSquadDisappearedOrBecameEmpty() {
        SquadWorkflowDO gone = row("READY");
        gone.setId(41L);
        SquadWorkflowDO empty = row("READY");
        empty.setId(43L);
        empty.setSquadId(SQUAD + 1);
        when(dao.listCleanup(any(), any(), eq(100))).thenReturn(List.of(gone, empty));
        when(assembler.assemble(WS, SQUAD)).thenThrow(new BizException(ErrorCode.SQUAD_NOT_FOUND));
        when(assembler.assemble(WS, SQUAD + 1))
                .thenThrow(new BizException(ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD));

        service.cleanupExpired();

        verify(dao).deleteById(gone.getId());
        verify(dao).deleteById(empty.getId());
    }

    @Test
    void cleanupToleratesANullResult() {
        when(dao.listCleanup(any(), any(), eq(100))).thenReturn(null);

        service.cleanupExpired();

        verify(dao, never()).deleteById(anyLong());
    }

    private WorkflowInput input() {
        WorkflowInput.Member member = new WorkflowInput.Member("11", "实现者", "开发", "AW_FS_DEV",
                "88", "7", "交付流程", "REQ", List.of(), List.of());
        return new WorkflowInput(WS, SQUAD, WorkflowInput.ANALYSIS_VERSION, INPUT_JSON,
                WorkflowInputAssembler.sha256Hex(INPUT_JSON), List.of(member), List.of());
    }

    private SquadWorkflowDO row(String status) {
        SquadWorkflowDO row = new SquadWorkflowDO();
        row.setId(GEN);
        row.setWorkspaceId(WS);
        row.setSquadId(SQUAD);
        row.setInputHash(input().inputHash());
        row.setAnalysisVersion(WorkflowInput.ANALYSIS_VERSION);
        row.setStatus(status);
        row.setAttemptId("attempt-1");
        row.setConversationId(CONV);
        row.setRequestedBy(USER);
        row.setInputJson(INPUT_JSON);
        row.setSubmissionCount(0);
        row.setRepairCount(0);
        row.setDeadlineAt(new Date(System.currentTimeMillis() + 60_000));
        row.setRowVersion(0);
        return row;
    }

    private SquadWorkflowDO insertedRow() {
        return row("GENERATING");
    }

    private String receipts(WorkflowSubmissionReceipt... rows) throws Exception {
        return MAPPER.writeValueAsString(List.of(rows));
    }
}
