package com.aliyun.autowonder.squad.workflow;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;

/**
 * Terminal-state convergence shared by the conversation sink, dispatch bridge and recovery task.
 * It only touches the snapshot row, which keeps the sink out of the conversation service's
 * dependency graph and prevents a READY snapshot from ever being overwritten by a late callback.
 */
@Component
public class SquadWorkflowFinalizer {

    static final String STATUS_GENERATING = "GENERATING";
    static final String STATUS_REPAIRING = "REPAIRING";
    static final String STATUS_READY = "READY";
    static final String STATUS_FAILED = "FAILED";

    private final SquadWorkflowDao dao;
    private final TransactionTemplate transactionTemplate;

    public SquadWorkflowFinalizer(SquadWorkflowDao dao,
            PlatformTransactionManager transactionManager) {
        this.dao = dao;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactionTemplate = template;
    }

    public boolean markFailed(long workspaceId, long generationId, String expectedAttemptId,
            String code, String detail) {
        Boolean changed = transactionTemplate.execute(status -> {
            SquadWorkflowDO row = dao.lockById(workspaceId, generationId);
            if (row == null || !isActive(row)) {
                return false;
            }
            if (expectedAttemptId != null && !expectedAttemptId.equals(row.getAttemptId())) {
                return false;
            }
            row.setStatus(STATUS_FAILED);
            row.setErrorCode(code);
            row.setErrorDetail(bound(detail));
            row.setCompletedAt(new Date());
            return dao.update(row) == 1;
        });
        return Boolean.TRUE.equals(changed);
    }

    public boolean claimDispatch(long workspaceId, long generationId, String attemptId) {
        return dao.claimDispatch(workspaceId, generationId, attemptId) == 1;
    }

    public boolean attachConversation(long workspaceId, long generationId, String attemptId,
            long conversationId, long chiefVersionId) {
        Boolean changed = transactionTemplate.execute(status -> {
            SquadWorkflowDO row = dao.lockById(workspaceId, generationId);
            if (row == null || !isActive(row) || !attemptId.equals(row.getAttemptId())) {
                return false;
            }
            row.setConversationId(conversationId);
            row.setChiefVersionId(chiefVersionId);
            return dao.update(row) == 1;
        });
        return Boolean.TRUE.equals(changed);
    }

    /**
     * The conversation reached a terminal turn. A snapshot that is still active at that moment can
     * never receive a valid submission through this attempt, so it must fail instead of waiting for
     * the deadline. A READY snapshot is already frozen and must stay untouched.
     */
    public void conversationTerminal(long workspaceId, long conversationId) {
        conversationTerminal(workspaceId, conversationId, null, null);
    }

    public void conversationTerminal(long workspaceId, long conversationId, String turnStatus,
            String error) {
        transactionTemplate.executeWithoutResult(status -> {
            SquadWorkflowDO found = dao.findByConversation(workspaceId, conversationId);
            if (found == null) {
                return;
            }
            SquadWorkflowDO row = dao.lockById(workspaceId, found.getId());
            if (row == null || !isActive(row)
                    || !java.util.Objects.equals(row.getConversationId(), conversationId)) {
                return;
            }
            row.setStatus(STATUS_FAILED);
            boolean executionFailed = "FAILED".equalsIgnoreCase(turnStatus);
            boolean executionCanceled = "CANCELED".equalsIgnoreCase(turnStatus);
            row.setErrorCode(executionFailed ? WorkflowErrorMessages.EXECUTION_FAILED
                    : executionCanceled ? WorkflowErrorMessages.EXECUTION_CANCELED
                    : WorkflowErrorMessages.NO_VALID_RESULT);
            row.setErrorDetail(executionFailed || executionCanceled ? bound(error)
                    : "conversation ended without an accepted submission");
            row.setCompletedAt(new Date());
            dao.update(row);
        });
    }

    public void markTimedOut(long workspaceId, long generationId) {
        transactionTemplate.executeWithoutResult(status -> {
            SquadWorkflowDO row = dao.lockById(workspaceId, generationId);
            if (row == null || !isActive(row) || row.getDeadlineAt() == null
                    || row.getDeadlineAt().after(new Date())) {
                return;
            }
            row.setStatus(STATUS_FAILED);
            row.setErrorCode(WorkflowErrorMessages.DEADLINE_EXCEEDED);
            row.setErrorDetail("generation deadline exceeded");
            row.setCompletedAt(new Date());
            dao.update(row);
        });
    }

    static boolean isActive(SquadWorkflowDO row) {
        return row != null && (STATUS_GENERATING.equals(row.getStatus())
                || STATUS_REPAIRING.equals(row.getStatus()));
    }

    private String bound(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.codePointCount(0, detail.length()) > 1000
                ? detail.substring(0, detail.offsetByCodePoints(0, 999)) + "…" : detail;
    }
}
