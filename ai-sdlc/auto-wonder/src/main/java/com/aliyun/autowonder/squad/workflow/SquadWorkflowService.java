package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Snapshot state machine and shared cache for the squad workflow graph. Every transition that
 * depends on the current attempt happens under a row lock, and the external Chief dispatch always
 * runs after the transaction has been committed so a slow model call never holds a database lock.
 */
@Service
public class SquadWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(SquadWorkflowService.class);

    static final String STATUS_GENERATING = "GENERATING";
    static final String STATUS_REPAIRING = "REPAIRING";
    static final String STATUS_READY = "READY";
    static final String STATUS_FAILED = "FAILED";
    static final List<String> ACTIVE_STATUSES = List.of(STATUS_GENERATING, STATUS_REPAIRING);

    static final int MAX_SUBMISSIONS = 3;
    static final int MAX_STORED_ERRORS = 20;
    private static final String REPAIR_INSTRUCTION_VERSION = "repair-v1";
    private static final String ACCEPTED_INSTRUCTION = REPAIR_INSTRUCTION_VERSION
            + ": No repair is needed; the result was accepted and the snapshot is frozen.";
    private static final long RECOVERY_DISPATCH_GRACE_MS = 60_000L;
    private static final int RECOVERY_BATCH = 100;
    private static final int RESERVE_ATTEMPTS = 3;
    private static final long RESERVE_BACKOFF_MS = 25L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowInputAssembler inputAssembler;
    private final WorkflowValidator validator;
    private final SquadWorkflowDao dao;
    private final SquadWorkflowFinalizer finalizer;
    private final SquadWorkflowConversationBridge bridge;
    private final TransactionTemplate transactionTemplate;
    private final int maxActiveGenerations;
    private final long deadlineMillis;
    private final long failedRetentionMillis;
    private final long successRetentionMillis;

    public SquadWorkflowService(WorkflowInputAssembler inputAssembler, WorkflowValidator validator,
            SquadWorkflowDao dao, SquadWorkflowFinalizer finalizer,
            SquadWorkflowConversationBridge bridge, PlatformTransactionManager transactionManager,
            @Value("${autowonder.squad.workflow.max-active-generations:2}") int maxActiveGenerations,
            @Value("${autowonder.squad.workflow.deadline-seconds:1200}") long deadlineSeconds,
            @Value("${autowonder.squad.workflow.retention-failed-days:7}") long failedRetentionDays,
            @Value("${autowonder.squad.workflow.retention-success-days:30}") long successRetentionDays) {
        this.inputAssembler = inputAssembler;
        this.validator = validator;
        this.dao = dao;
        this.finalizer = finalizer;
        this.bridge = bridge;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactionTemplate = template;
        this.maxActiveGenerations = Math.max(1, maxActiveGenerations);
        this.deadlineMillis = Math.max(1, deadlineSeconds) * 1000L;
        this.failedRetentionMillis = Math.max(0, failedRetentionDays) * 86_400_000L;
        this.successRetentionMillis = Math.max(0, successRetentionDays) * 86_400_000L;
    }

    // ------------------------------------------------------------------ reads

    public SquadWorkflowView getCurrent(long workspaceId, long squadId) {
        WorkflowInput input;
        try {
            input = inputAssembler.assemble(workspaceId, squadId);
        } catch (BizException e) {
            if (ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD.getCode().equals(e.getCode())) {
                return emptyView();
            }
            throw e;
        }
        SquadWorkflowDO row = dao.findByKey(workspaceId, squadId, input.inputHash(),
                input.analysisVersion());
        return row == null ? missingView(input) : toView(row);
    }

    public SquadWorkflowView getGeneration(long workspaceId, long squadId, long generationId) {
        SquadWorkflowDO row = dao.findById(workspaceId, generationId);
        if (row == null || row.getSquadId() == null || row.getSquadId() != squadId) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_GENERATION_NOT_FOUND);
        }
        return toView(row);
    }

    // ------------------------------------------------------------- generation

    public SquadWorkflowView ensureGeneration(long workspaceId, long squadId, long userId,
            String expectedInputHash, boolean retry) {
        WorkflowInput input;
        try {
            input = inputAssembler.assemble(workspaceId, squadId);
        } catch (BizException e) {
            if (ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD.getCode().equals(e.getCode())) {
                return emptyView();
            }
            throw e;
        }
        if (expectedInputHash != null && !expectedInputHash.isBlank()
                && !expectedInputHash.equals(input.inputHash())) {
            throw new SquadWorkflowStaleInputException(currentView(input));
        }
        ReserveOutcome outcome = reserve(input, userId, retry);
        if (!outcome.dispatch()) {
            return toView(outcome.row());
        }
        dispatch(outcome.row(), input);
        SquadWorkflowDO stored = dao.findById(workspaceId, outcome.row().getId());
        return toView(stored == null ? outcome.row() : stored);
    }

    private SquadWorkflowView currentView(WorkflowInput input) {
        SquadWorkflowDO row = dao.findByKey(input.workspaceId(), input.squadId(),
                input.inputHash(), input.analysisVersion());
        return row == null ? missingView(input) : toView(row);
    }

    private ReserveOutcome reserve(WorkflowInput input, long userId, boolean retry) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> reserveInTransaction(input, userId, retry));
            } catch (DuplicateKeyException e) {
                // Another instance won the same-key insert. Sharing its attempt is the correct answer,
                // not an error: the cache key, not the caller, owns the generation.
                return shareExisting(input, e);
            } catch (ConcurrencyFailureException e) {
                // Two first triggers on an empty workspace hold the same gap lock on the quota scan,
                // so InnoDB may pick one of them as the deadlock victim. Retrying re-reads the row
                // the winner committed instead of surfacing a spurious version conflict.
                if (attempt >= RESERVE_ATTEMPTS) {
                    throw new BizException(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT, e);
                }
                if (!backoff(attempt)) {
                    throw new BizException(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT, e);
                }
            }
        }
    }

    private ReserveOutcome shareExisting(WorkflowInput input, RuntimeException cause) {
        SquadWorkflowDO existing = dao.findByKey(input.workspaceId(), input.squadId(),
                input.inputHash(), input.analysisVersion());
        if (existing != null) {
            return new ReserveOutcome(existing, false);
        }
        throw new BizException(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT, cause);
    }

    private boolean backoff(int attempt) {
        try {
            Thread.sleep(RESERVE_BACKOFF_MS * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ReserveOutcome reserveInTransaction(WorkflowInput input, long userId, boolean retry) {
        long workspaceId = input.workspaceId();
        SquadWorkflowDO existing = dao.findByKey(workspaceId, input.squadId(), input.inputHash(),
                input.analysisVersion());
        if (existing != null) {
            if (!STATUS_FAILED.equals(existing.getStatus()) || !retry) {
                return new ReserveOutcome(existing, false);
            }
            SquadWorkflowDO locked = dao.lockById(workspaceId, existing.getId());
            if (locked == null || !STATUS_FAILED.equals(locked.getStatus())) {
                return new ReserveOutcome(locked == null ? existing : locked, false);
            }
            checkQuota(workspaceId);
            resetForAttempt(locked, userId);
            requireUpdated(locked);
            return new ReserveOutcome(locked, true);
        }
        checkQuota(workspaceId);
        SquadWorkflowDO row = newRow(input, userId);
        dao.insert(row);
        return new ReserveOutcome(row, true);
    }

    private void checkQuota(long workspaceId) {
        List<Long> active = dao.lockActiveIds(workspaceId, ACTIVE_STATUSES, new Date());
        if (active != null && active.size() >= maxActiveGenerations) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_BUSY);
        }
    }

    private SquadWorkflowDO newRow(WorkflowInput input, long userId) {
        SquadWorkflowDO row = new SquadWorkflowDO();
        row.setWorkspaceId(input.workspaceId());
        row.setSquadId(input.squadId());
        row.setInputHash(input.inputHash());
        row.setAnalysisVersion(input.analysisVersion());
        row.setStatus(STATUS_GENERATING);
        row.setAttemptId(newAttemptId());
        row.setRequestedBy(userId);
        row.setInputJson(input.payloadJson());
        row.setSubmissionCount(0);
        row.setRepairCount(0);
        row.setDeadlineAt(new Date(System.currentTimeMillis() + deadlineMillis));
        row.setRowVersion(0);
        return row;
    }

    private void resetForAttempt(SquadWorkflowDO row, long userId) {
        row.setStatus(STATUS_GENERATING);
        row.setAttemptId(newAttemptId());
        row.setConversationId(null);
        row.setTurnId(null);
        row.setChiefVersionId(null);
        row.setRequestedBy(userId);
        row.setGraphJson(null);
        row.setSubmissionReceiptsJson(null);
        row.setSubmissionCount(0);
        row.setRepairCount(0);
        row.setDeadlineAt(new Date(System.currentTimeMillis() + deadlineMillis));
        row.setErrorCode(null);
        row.setErrorDetail(null);
        row.setCompletedAt(null);
    }

    private void dispatch(SquadWorkflowDO row, WorkflowInput input) {
        try {
            SquadWorkflowConversationBridge.Attachment attachment = bridge.start(row, input);
            finalizer.attachConversation(row.getWorkspaceId(), row.getId(), row.getAttemptId(),
                    attachment.conversationId(), attachment.chiefVersionId());
        } catch (BizException e) {
            String code = ErrorCode.SQUAD_WORKFLOW_CHIEF_UNAVAILABLE.getCode().equals(e.getCode())
                    ? WorkflowErrorMessages.CHIEF_UNAVAILABLE : WorkflowErrorMessages.DISPATCH_FAILED;
            finalizer.markFailed(row.getWorkspaceId(), row.getId(), row.getAttemptId(), code,
                    safeMessage(e));
            log.warn("squad workflow dispatch failed workspaceId={} squadId={} generationId={} code={}",
                    row.getWorkspaceId(), row.getSquadId(), row.getId(), e.getCode());
        } catch (RuntimeException e) {
            finalizer.markFailed(row.getWorkspaceId(), row.getId(), row.getAttemptId(),
                    WorkflowErrorMessages.DISPATCH_FAILED, safeMessage(e));
            log.warn("squad workflow dispatch failed workspaceId={} squadId={} generationId={}",
                    row.getWorkspaceId(), row.getSquadId(), row.getId(), e);
        }
    }

    // ------------------------------------------------------------- submission

    public WorkflowFeedback acceptSubmission(long workspaceId, long conversationId,
            String generationId, String attemptId, String submissionId, String payloadJson) {
        long id = parseId(generationId);
        if (submissionId == null || submissionId.isBlank() || submissionId.length() > 128
                || attemptId == null || attemptId.isBlank()) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST);
        }
        String digest = WorkflowInputAssembler.sha256Hex(payloadJson == null ? "" : payloadJson);
        WorkflowFeedback feedback = transactionTemplate.execute(status -> acceptInTransaction(
                workspaceId, conversationId, id, attemptId, submissionId, digest, payloadJson));
        return feedback == null ? rejectedFor(generationId, attemptId, submissionId,
                WorkflowValidator.INVALID_JSON, "/payloadJson",
                "submission could not be processed") : feedback;
    }

    private WorkflowFeedback acceptInTransaction(long workspaceId, long conversationId, long id,
            String attemptId, String submissionId, String digest, String payloadJson) {
        SquadWorkflowDO row = dao.lockById(workspaceId, id);
        if (row == null) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_GENERATION_NOT_FOUND);
        }
        if (row.getConversationId() == null || row.getConversationId() != conversationId) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_ATTEMPT_MISMATCH);
        }
        if (STATUS_READY.equals(row.getStatus())) {
            return replayReady(row, submissionId, digest);
        }
        if (!SquadWorkflowFinalizer.isActive(row)) {
            return contextual(rejected(submissionId, "NOT_AVAILABLE", "",
                    "this generation can no longer accept submissions"), row, used(row), false);
        }
        if (!attemptId.equals(row.getAttemptId())) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_ATTEMPT_MISMATCH);
        }
        if (row.getDeadlineAt() != null && !row.getDeadlineAt().after(new Date())) {
            markTerminal(row, WorkflowErrorMessages.DEADLINE_EXCEEDED, "generation deadline exceeded");
            return contextual(rejected(submissionId, WorkflowErrorMessages.DEADLINE_EXCEEDED, "",
                    "the generation deadline has passed"), row, used(row), false);
        }
        List<WorkflowSubmissionReceipt> receipts = readReceipts(row.getSubmissionReceiptsJson());
        for (WorkflowSubmissionReceipt receipt : receipts) {
            if (submissionId.equals(receipt.submissionId())) {
                if (!receipt.matches(submissionId, digest)) {
                    return contextual(rejected(submissionId, WorkflowValidator.ID_DUPLICATE,
                            "/submissionId", "submissionId was already used for different content"),
                            row, used(row), SquadWorkflowFinalizer.isActive(row));
                }
                return replay(submissionId, receipt, row);
            }
        }
        if (receipts.size() >= MAX_SUBMISSIONS) {
            markTerminal(row, WorkflowErrorMessages.SUBMISSION_LIMIT,
                    "no submission budget remains for this attempt");
            return contextual(rejected(submissionId, WorkflowErrorMessages.SUBMISSION_LIMIT, "",
                    "no submission budget remains for this attempt"), row, used(row), false);
        }
        WorkflowInput input;
        try {
            input = WorkflowInputCodec.fromPayload(workspaceId, row.getSquadId(), row.getInputJson());
        } catch (RuntimeException e) {
            markTerminal(row, WorkflowErrorMessages.INTERNAL_ERROR, "stored input is not readable");
            return contextual(rejected(submissionId, WorkflowErrorMessages.INTERNAL_ERROR, "",
                    "the stored analysis input is not readable"), row, used(row), false);
        }
        WorkflowFeedback feedback = validator.validateJson(input, payloadJson)
                .withSubmissionId(submissionId);
        String submittedAt = Instant.now().toString();
        boolean accepted = feedback.accepted();
        // The live feedback lists every validator error (up to its own cap), while the receipt stores
        // at most MAX_STORED_ERRORS. Persist the flag whenever the stored list lost anything, even if
        // the validator itself never reached its cap.
        boolean receiptTruncated = !accepted
                && (feedback.errorsTruncated() || feedback.errors().size() > MAX_STORED_ERRORS);
        receipts.add(new WorkflowSubmissionReceipt(submissionId, digest,
                accepted ? WorkflowFeedback.ACCEPTED : WorkflowFeedback.REJECTED, submittedAt,
                accepted ? List.of() : boundErrors(feedback.errors()),
                bound(feedback.summary(), 300), receiptTruncated));
        row.setSubmissionCount(receipts.size());
        row.setRepairCount((int) receipts.stream()
                .filter(receipt -> WorkflowFeedback.REJECTED.equals(receipt.status())).count());
        row.setSubmissionReceiptsJson(writeReceipts(receipts));
        if (accepted) {
            row.setStatus(STATUS_READY);
            row.setGraphJson(payloadJson);
            row.setErrorCode(null);
            row.setErrorDetail(null);
            row.setCompletedAt(new Date());
        } else if (receipts.size() >= MAX_SUBMISSIONS) {
            row.setStatus(STATUS_FAILED);
            row.setErrorCode(WorkflowErrorMessages.SUBMISSION_LIMIT);
            row.setErrorDetail("submission budget exhausted after repeated validation failures");
            row.setCompletedAt(new Date());
        } else {
            row.setStatus(STATUS_REPAIRING);
        }
        requireUpdated(row);
        return contextual(feedback, row, receipts.size(),
                !accepted && receipts.size() < MAX_SUBMISSIONS, feedback.errorsTruncated());
    }

    private WorkflowFeedback replayReady(SquadWorkflowDO row, String submissionId, String digest) {
        for (WorkflowSubmissionReceipt receipt : readReceipts(row.getSubmissionReceiptsJson())) {
            if (submissionId.equals(receipt.submissionId())) {
                if (!receipt.matches(submissionId, digest)) {
                    return contextual(rejected(submissionId, WorkflowValidator.ID_DUPLICATE,
                            "/submissionId", "submissionId was already used for different content"),
                            row, used(row), false);
                }
                return replay(submissionId, receipt, row);
            }
        }
        return contextual(new WorkflowFeedback(WorkflowFeedback.REJECTED, submissionId,
                List.of(new WorkflowValidationError(WorkflowValidator.ID_DUPLICATE, "/submissionId",
                        "this generation is already accepted", "")),
                "generation already accepted", null), row, used(row), false);
    }

    private WorkflowFeedback replay(String submissionId, WorkflowSubmissionReceipt receipt,
            SquadWorkflowDO row) {
        if (WorkflowFeedback.ACCEPTED.equals(receipt.status())) {
            return contextual(new WorkflowFeedback(WorkflowFeedback.ACCEPTED, submissionId, List.of(),
                    "submission already accepted", parseGraph(row.getGraphJson())), row, used(row),
                    false);
        }
        return contextual(new WorkflowFeedback(WorkflowFeedback.REJECTED, submissionId,
                receipt.errors() == null ? List.of() : receipt.errors(), receipt.summary(), null),
                row, used(row), SquadWorkflowFinalizer.isActive(row), receipt.errorsTruncated());
    }

    private void markTerminal(SquadWorkflowDO row, String code, String detail) {
        row.setStatus(STATUS_FAILED);
        row.setErrorCode(code);
        row.setErrorDetail(detail);
        row.setCompletedAt(new Date());
        requireUpdated(row);
    }

    private void requireUpdated(SquadWorkflowDO row) {
        if (dao.update(row) != 1) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_VERSION_CONFLICT);
        }
    }

    // --------------------------------------------------------- recovery/cleanup

    public void recover() {
        Date now = new Date();
        for (SquadWorkflowDO row : safe(dao.listExpired(now, RECOVERY_BATCH))) {
            finalizer.markTimedOut(row.getWorkspaceId(), row.getId());
        }
        Date cutoff = new Date(now.getTime() - RECOVERY_DISPATCH_GRACE_MS);
        for (SquadWorkflowDO row : safe(dao.listUndispatched(cutoff, RECOVERY_BATCH))) {
            if (!finalizer.claimDispatch(row.getWorkspaceId(), row.getId(), row.getAttemptId())) {
                continue;
            }
            if (row.getInputJson() == null || row.getInputJson().isBlank()) {
                finalizer.markFailed(row.getWorkspaceId(), row.getId(), row.getAttemptId(),
                        WorkflowErrorMessages.INTERNAL_ERROR, "stored input is not readable");
                continue;
            }
            try {
                WorkflowInput input = WorkflowInputCodec.fromPayload(row.getWorkspaceId(),
                        row.getSquadId(), row.getInputJson());
                dispatch(row, input);
            } catch (RuntimeException e) {
                finalizer.markFailed(row.getWorkspaceId(), row.getId(), row.getAttemptId(),
                        WorkflowErrorMessages.INTERNAL_ERROR, safeMessage(e));
            }
        }
    }

    public void cleanupExpired() {
        Date now = new Date();
        List<SquadWorkflowDO> rows = safe(dao.listCleanup(
                new Date(now.getTime() - failedRetentionMillis),
                new Date(now.getTime() - successRetentionMillis), RECOVERY_BATCH));
        Map<String, WorkflowInput> currents = new HashMap<>();
        Set<String> unreadable = new HashSet<>();
        for (SquadWorkflowDO row : rows) {
            if (STATUS_READY.equals(row.getStatus())) {
                String key = row.getWorkspaceId() + ":" + row.getSquadId();
                if (unreadable.contains(key)) {
                    continue;
                }
                if (!currents.containsKey(key)) {
                    try {
                        currents.put(key,
                                inputAssembler.assemble(row.getWorkspaceId(), row.getSquadId()));
                    } catch (BizException e) {
                        if (ErrorCode.SQUAD_NOT_FOUND.getCode().equals(e.getCode())
                                || ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD.getCode().equals(e.getCode())) {
                            currents.put(key, null);
                        } else {
                            unreadable.add(key);
                            continue;
                        }
                    } catch (RuntimeException e) {
                        unreadable.add(key);
                        continue;
                    }
                }
                WorkflowInput current = currents.get(key);
                if (current != null && current.inputHash().equals(row.getInputHash())
                        && current.analysisVersion().equals(row.getAnalysisVersion())) {
                    // A→B→A rollback makes the newest row stale and an older one current again.
                    continue;
                }
            }
            dao.deleteById(row.getId());
        }
    }

    private <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    // ---------------------------------------------------------------- mapping

    private SquadWorkflowView toView(SquadWorkflowDO row) {
        SquadWorkflowView.ErrorInfo error = null;
        if (STATUS_FAILED.equals(row.getStatus())) {
            String code = row.getErrorCode() == null ? WorkflowErrorMessages.INTERNAL_ERROR
                    : row.getErrorCode();
            error = new SquadWorkflowView.ErrorInfo(code, WorkflowErrorMessages.message(code, row.getErrorDetail()),
                    WorkflowErrorMessages.retryable(code));
        }
        return new SquadWorkflowView(row.getStatus(), row.getInputHash(), row.getAnalysisVersion(),
                row.getId(), row.getAttemptId(), row.getCompletedAt(), error,
                STATUS_READY.equals(row.getStatus()) ? parseGraph(row.getGraphJson()) : null);
    }

    private SquadWorkflowView missingView(WorkflowInput input) {
        return new SquadWorkflowView(SquadWorkflowView.MISSING, input.inputHash(),
                input.analysisVersion(), null, null, null, null, null);
    }

    private SquadWorkflowView emptyView() {
        return new SquadWorkflowView(SquadWorkflowView.EMPTY, null,
                WorkflowInput.ANALYSIS_VERSION, null, null, null, null, null);
    }

    private JsonNode parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(graphJson);
        } catch (Exception e) {
            return null;
        }
    }

    private WorkflowFeedback rejected(String submissionId, String code, String path, String message) {
        return new WorkflowFeedback(WorkflowFeedback.REJECTED, submissionId,
                List.of(new WorkflowValidationError(code, path, message, "")), message, null);
    }

    private WorkflowFeedback rejectedFor(String generationId, String attemptId, String submissionId,
            String code, String path, String message) {
        return rejected(submissionId, code, path, message).contextual(generationId, attemptId, 0,
                false, false, repairInstruction(0, false));
    }

    private WorkflowFeedback contextual(WorkflowFeedback feedback, SquadWorkflowDO row, int used,
            boolean retryable) {
        return contextual(feedback, row, used, retryable, feedback.errorsTruncated());
    }

    private WorkflowFeedback contextual(WorkflowFeedback feedback, SquadWorkflowDO row, int used,
            boolean retryable, boolean errorsTruncated) {
        int remaining = retryable ? Math.max(0, MAX_SUBMISSIONS - used) : 0;
        // The submission tool declares repairInstruction as a non-null string, so ACCEPTED feedback
        // must carry an explicit "nothing to repair" instruction instead of null.
        String instruction = feedback.accepted() ? ACCEPTED_INSTRUCTION
                : repairInstruction(remaining, retryable);
        return feedback.contextual(String.valueOf(row.getId()), row.getAttemptId(), remaining,
                retryable, errorsTruncated, instruction);
    }

    private static String newAttemptId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int used(SquadWorkflowDO row) {
        return row.getSubmissionCount() == null ? 0 : row.getSubmissionCount();
    }

    private String repairInstruction(int remaining, boolean retryable) {
        if (!retryable) {
            return REPAIR_INSTRUCTION_VERSION
                    + ": This generation is closed to further submissions.";
        }
        return REPAIR_INSTRUCTION_VERSION
                + ": Fix every field listed in errors (paths are JSON Pointers), then resubmit the "
                + "complete result with a new submissionId. Remaining submissions: " + remaining + ".";
    }

    private List<WorkflowValidationError> boundErrors(List<WorkflowValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(errors.subList(0, Math.min(errors.size(), MAX_STORED_ERRORS)));
    }

    private String bound(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private List<WorkflowSubmissionReceipt> readReceipts(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            WorkflowSubmissionReceipt[] rows = MAPPER.readValue(json, WorkflowSubmissionReceipt[].class);
            return new ArrayList<>(List.of(rows));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeReceipts(List<WorkflowSubmissionReceipt> receipts) {
        try {
            return MAPPER.writeValueAsString(receipts);
        } catch (Exception e) {
            throw new IllegalStateException("submission receipts serialization failed", e);
        }
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_GENERATION_NOT_FOUND);
        }
    }

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return bound(message == null || message.isBlank()
                ? e.getClass().getSimpleName() : message, 1000);
    }

    private record ReserveOutcome(SquadWorkflowDO row, boolean dispatch) {
    }
}
