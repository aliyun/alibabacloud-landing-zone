package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/** Source-aware Run state updates. Workitem execution never enters this adapter. */
@Service
public class ScheduledTaskRunService {
    private static final long SYSTEM_USER_ID = 0L;
    private final ScheduledTaskRunDao runDao;
    private AuditLogService auditLogService;
    private ScheduledTaskNotificationService notificationService;
    private ScheduledTaskMetrics metrics;

    public ScheduledTaskRunService(ScheduledTaskRunDao runDao) { this.runDao = runDao; }

    @Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) { this.auditLogService = auditLogService; }
    @Autowired(required = false)
    public void setObservability(ScheduledTaskNotificationService notificationService, ScheduledTaskMetrics metrics) {
        this.notificationService = notificationService; this.metrics = metrics;
    }

    public void completeFromDispatch(DispatchDO dispatch, boolean success, String summary, String error) {
        if (dispatch == null || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN
                || dispatch.getTenantId() == null || dispatch.getWorkitemId() == null) return;
        ScheduledTaskRunDO run = runDao.findById(dispatch.getTenantId(), dispatch.getWorkitemId());
        if (run == null || run.getStatus() == null || run.getVersion() == null || terminal(run.getStatus())) return;
        finish(run, success ? "SUCCEEDED" : "FAILED", summary, error, SYSTEM_USER_ID);
    }

    /** Mirrors an accepted Runtime progress transition without touching terminal Runs. */
    public void markRunningFromDispatch(DispatchDO dispatch) {
        if (dispatch == null || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN
                || dispatch.getTenantId() == null || dispatch.getWorkitemId() == null) return;
        ScheduledTaskRunDO run = runDao.findById(dispatch.getTenantId(), dispatch.getWorkitemId());
        if (run == null || run.getVersion() == null || terminal(run.getStatus())) return;
        if ("WAITING_EXECUTOR".equals(run.getStatus())) {
            transitionSystem(run, "WAITING_EXECUTOR", "RUNNING", SYSTEM_USER_ID);
        }
    }

    /** Human lifecycle commands are workspace-scoped and always compare the UI's Run version. */
    @Transactional
    public ScheduledTaskRunDO transition(long workspaceId, long runId, Integer version, String target, long userId) {
        if (workspaceId <= 0 || runId <= 0 || userId <= 0 || version == null) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VALIDATION_FAILED);
        }
        ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
        if (run == null || !Long.valueOf(workspaceId).equals(run.getWorkspaceId())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }
        if (!version.equals(run.getVersion()) || !canTransition(run.getStatus(), target)) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE);
        }
        if ("CANCELED".equals(target)) {
            if (!finish(run, target, null, "CANCELED", userId)) {
                throw new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
            }
            audit(run, userId, target);
            return run;
        }
        if (runDao.updateStatus(workspaceId, runId, run.getStatus(), target, version, userId) != 1) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
        }
        run.setStatus(target); run.setVersion(version + 1); run.setModifierId(userId);
        if (terminal(target)) run.setFinishedAt(new java.util.Date());
        audit(run, userId, target);
        observed(run, target, null);
        return run;
    }

    /** Used by scheduler/orchestrator status steps so every persisted state emits the same signal. */
    public boolean transitionSystem(ScheduledTaskRunDO run, String expected, String target, long userId) {
        if (run == null || run.getWorkspaceId() == null || run.getId() == null || run.getVersion() == null) return false;
        if (runDao.updateStatus(run.getWorkspaceId(), run.getId(), expected, target, run.getVersion(), userId) != 1) return false;
        run.setStatus(target); run.setVersion(run.getVersion() + 1); run.setModifierId(userId); observed(run, target, null);
        return true;
    }

    /** The sole terminal persistence/observability path for scheduled Runs. */
    public boolean finish(ScheduledTaskRunDO run, String target, String summary, String error, long userId) {
        if (run == null || run.getWorkspaceId() == null || run.getId() == null || run.getVersion() == null || terminal(run.getStatus())) return false;
        if (runDao.updateTerminalResult(run.getWorkspaceId(), run.getId(), run.getStatus(), target, summary, error, run.getVersion(), userId) != 1) return false;
        run.setStatus(target); run.setResultSummary(summary); run.setError(error);
        run.setFinishedAt(new java.util.Date()); run.setVersion(run.getVersion() + 1); observed(run, target, error);
        return true;
    }
    public boolean markCancelPending(ScheduledTaskRunDO run, long userId) {
        if (run == null || run.getVersion() == null || !"PAUSED".equals(run.getStatus())) return false;
        if (runDao.markCancelPending(run.getWorkspaceId(), run.getId(), run.getVersion(), userId) != 1) return false;
        run.setError("CANCEL_PENDING"); run.setVersion(run.getVersion() + 1); return true;
    }
    public boolean markCancelIntent(ScheduledTaskRunDO run, long userId) {
        if (run == null || run.getVersion() == null || terminal(run.getStatus())) return false;
        if (runDao.markCancelIntent(run.getWorkspaceId(), run.getId(), run.getVersion(), userId) != 1) return false;
        run.setError("CANCEL_PENDING"); run.setVersion(run.getVersion() + 1); return true;
    }

    private void observed(ScheduledTaskRunDO run, String status, String reason) {
        if (notificationService != null) notificationService.status(run, reason);
        if (metrics != null) {
            metrics.status(status, reason);
            if (terminal(status) && run.getStartedAt() != null) metrics.duration(System.currentTimeMillis() - run.getStartedAt().getTime());
        }
    }

    /** For successful CAS operations whose SQL is intentionally specialized (e.g. initializeExecution). */
    public void observePersistedTransition(ScheduledTaskRunDO run, String reason) {
        if (run != null && run.getStatus() != null) observed(run, run.getStatus(), reason);
    }

    private boolean canTransition(String source, String target) {
        if ("PAUSED".equals(target)) return "QUEUED".equals(source) || "WAITING_EXECUTOR".equals(source)
                || "RUNNING".equals(source) || "WAITING_HUMAN".equals(source);
        if ("QUEUED".equals(target)) return "PAUSED".equals(source);
        if ("CANCELED".equals(target)) return !terminal(source);
        return false;
    }

    private void audit(ScheduledTaskRunDO run, long userId, String target) {
        if (auditLogService == null) return;
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(run.getWorkspaceId()); record.setActorId(userId); record.setActorType("HUMAN");
        record.setModule("SCHEDULED_TASK"); record.setAction("RUN_" + target);
        record.setTargetType("SCHEDULED_TASK_RUN"); record.setTargetId(run.getId());
        record.setTriggerType("EVENT"); record.setTriggerSource("WEB");
        record.detail("scheduledTaskId", run.getScheduledTaskId()).detail("status", target).detail("version", run.getVersion());
        auditLogService.recordRequired(record);
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status)
                || "CANCELED".equals(status) || "SKIPPED".equals(status);
    }
}
