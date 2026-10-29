package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunService;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Service
public class DispatchPauseService {

    public enum CompletionDisposition {
        NOT_PAUSING,
        PAUSED,
        REJECTED
    }

    private static final long SYSTEM_USER_ID = 0L;

    private final DispatchDao dispatchDao;
    private final DispatchCheckpointService checkpointService;
    private final DispatchControlTransport transport;
    private ScheduledTaskRunDao scheduledRunDao;
    private ScheduledTaskRunService scheduledRunService;
    private DispatchRecoveryService recovery;
    private PresenceManager presenceManager;
    private DispatchRuntimeEventDao runtimeEvents;
    private TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeEvents(DispatchRuntimeEventDao runtimeEvents) {
        this.runtimeEvents = runtimeEvents;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPresenceManager(PresenceManager presenceManager) {
        this.presenceManager = presenceManager;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecovery(DispatchRecoveryService recovery) {
        this.recovery = recovery;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return tx == null ? action.get() : tx.execute(status -> action.get());
    }

    public DispatchPauseService(DispatchDao dispatchDao,
            DispatchCheckpointService checkpointService,
            DispatchControlTransport transport) {
        this.dispatchDao = dispatchDao;
        this.checkpointService = checkpointService;
        this.transport = transport;
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setScheduledRunControl(ScheduledTaskRunDao scheduledRunDao, ScheduledTaskRunService scheduledRunService) {
        this.scheduledRunDao = scheduledRunDao; this.scheduledRunService = scheduledRunService;
    }

    public DispatchDO requestPause(long tenantId, long workitemId, long dispatchId, long userId) {
        DispatchDO dispatch = requireDispatch(tenantId, workitemId, dispatchId);
        return requestPause(dispatch, tenantId, userId);
    }

    /** Source-aware administrative pause used by scheduled Run controls. */
    public DispatchDO requestPauseScheduledRun(long workspaceId, long runId, long dispatchId, long userId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || !Long.valueOf(workspaceId).equals(dispatch.getTenantId())
                || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN
                || !Long.valueOf(runId).equals(dispatch.getWorkitemId())) throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        return requestPause(dispatch, workspaceId, userId);
    }

    private DispatchDO requestPause(DispatchDO dispatch, long tenantId, long userId) {
        if (dispatch.executionSourceType() == ExecutionSourceType.WORKITEM && recovery != null) {
            return requestWorkitemPause(dispatch, tenantId, userId);
        }
        return requestLegacyPause(dispatch, tenantId, userId);
    }

    /** Workitem pause shares the read-model policy and reads all state after the control lock. */
    private DispatchDO requestWorkitemPause(DispatchDO stale, long tenantId, long userId) {
        boolean[] resend = {false};
        DispatchDO updated = inTransaction(() -> {
            recovery.lockWorkitemControl(stale);
            DispatchDO dispatch = dispatchDao.findById(stale.getId());
            if (dispatch == null || dispatch.getTenantId() != tenantId
                    || dispatch.executionSourceType() != ExecutionSourceType.WORKITEM
                    || !java.util.Objects.equals(dispatch.getWorkitemId(), stale.getWorkitemId())) {
                throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
            }
            if (recovery.cancelRequested(tenantId, dispatch.getId())) {
                throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.pause.invalid_state", new Object[0], "正在结束本次执行，不能暂停");
            }
            if (DispatchStatus.PAUSED.equals(dispatch.getStatus())) {
                return dispatch;
            }
            if (DispatchStatus.PAUSING.equals(dispatch.getStatus())) {
                resend[0] = true;
                return dispatch;
            }
            boolean executorOnline = presenceManager == null
                    || (dispatch.getExecutorId() != null && presenceManager.isExecutorOnline(dispatch.getExecutorId()));
            boolean runtimeFailed = runtimeEvents != null
                    && !DispatchStatus.PENDING.equals(dispatch.getStatus())
                    && !DispatchStatus.isTerminal(dispatch.getStatus())
                    && DispatchRuntimeFailure.isFailure(DispatchRuntimeFailure.latest(
                    runtimeEvents.listByDispatchInArrivalOrder(tenantId, dispatch.getId())));
            DispatchService.PauseResolution policy = DispatchService.resolvePauseCapability(
                    dispatch, dispatchDao.listByWorkitem(tenantId, dispatch.getWorkitemId()),
                    recovery.closed(tenantId, dispatch.getWorkitemId()), false, runtimeFailed, executorOnline);
            if (!policy.allowed()) {
                throw BizException.keyed(ErrorCode.CONFLICT, policy.errorKey(), new Object[0], policy.diagnostic());
            }
            String clearedError = DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus()) ? "" : null;
            int rows = dispatchDao.updateStatus(dispatch.getId(), tenantId, DispatchStatus.PAUSING,
                    null, null, null, null, clearedError, dispatch.getVersion(), userId);
            if (rows != 1) {
                throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.state_changed", new Object[0], "执行状态已变化，请刷新后重试");
            }
            dispatch.setStatus(DispatchStatus.PAUSING);
            dispatch.setError(null);
            dispatch.setVersion(dispatch.getVersion() + 1);
            recovery.recordControlRequest(dispatch, "PAUSE", "REQUESTED",
                    userId == 0 ? "REWORK" : "USER", userId, null, false, false, null);
            return dispatch;
        });
        if (!resend[0] && !DispatchStatus.PAUSING.equals(updated.getStatus())) {
            return updated;
        }
        try {
            if (!ResultCommitActions.defer("pause:" + updated.getId(), () -> transport.pause(updated)))
                transport.pause(updated);
        } catch (RuntimeException sendFailed) {
            markPauseFailedAfterSendFailure(updated, tenantId, userId, sendFailed);
            throw sendFailed;
        }
        return updated;
    }

    private DispatchDO requestLegacyPause(DispatchDO dispatch, long tenantId, long userId) {
        if (recovery != null && dispatch.executionSourceType() == ExecutionSourceType.WORKITEM
                && recovery.cancelRequested(tenantId, dispatch.getId())) {
            throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.pause.invalid_state", new Object[0], "当前执行状态不能暂停");
        }
        if (DispatchStatus.PAUSED.equals(dispatch.getStatus())) {
            return dispatch;
        }
        if (DispatchStatus.PAUSING.equals(dispatch.getStatus())) {
            if (!ResultCommitActions.defer("pause:" + dispatch.getId(), () -> transport.pause(dispatch)))
                transport.pause(dispatch);
            return dispatch;
        }
        if (!DispatchStatus.isPauseable(dispatch.getStatus())
                && !DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus())) {
            throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.pause.invalid_state", new Object[0], "当前执行状态不能暂停");
        }
        DispatchDO updated = inTransaction(() -> {
            if (recovery != null && dispatch.executionSourceType() == ExecutionSourceType.WORKITEM) {
                recovery.lockWorkitemControl(dispatch);
            }
            String clearedError = DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus()) ? "" : null;
            int rows = dispatchDao.updateStatus(dispatch.getId(), tenantId, DispatchStatus.PAUSING,
                    null, null, null, null, clearedError, dispatch.getVersion(), userId);
            if (rows != 1) {
                throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.state_changed", new Object[0], "执行状态已变化，请刷新后重试");
            }
            dispatch.setStatus(DispatchStatus.PAUSING);
            dispatch.setError(null);
            dispatch.setVersion(dispatch.getVersion() + 1);
            if (recovery != null) {
                recovery.recordControlRequest(dispatch, "PAUSE", "REQUESTED", "USER", userId,
                        null, false, false, null);
            }
            return dispatch;
        });
        try {
            if (!ResultCommitActions.defer("pause:" + updated.getId(), () -> transport.pause(updated)))
                transport.pause(updated);
        } catch (RuntimeException sendFailed) {
            markPauseFailedAfterSendFailure(updated, tenantId, userId, sendFailed);
            throw sendFailed;
        }
        return updated;
    }

    public boolean onPaused(long tenantId, long executorId, long dispatchId,
            long checkpointSeq, String checkpointSha256) {
        DispatchDO owned = ownedDispatch(tenantId, executorId, dispatchId);
        if (owned == null) {
            return false;
        }
        if (DispatchStatus.PAUSED.equals(owned.getStatus())) {
            return inTransaction(() -> {
                if (recovery != null) recovery.lockWorkitemControl(owned);
                DispatchDO current = ownedDispatch(tenantId, executorId, dispatchId);
                return current != null && DispatchStatus.PAUSED.equals(current.getStatus())
                        && (recovery == null || !recovery.cancelRequested(tenantId, dispatchId))
                        && checkpointService.confirmPauseReceipt(tenantId, executorId, dispatchId, checkpointSeq, checkpointSha256);
            });
        }
        boolean paused = inTransaction(() -> {
            if (recovery != null) recovery.lockWorkitemControl(owned);
            DispatchDO dispatch = ownedDispatch(tenantId, executorId, dispatchId);
            if (dispatch == null || (!DispatchStatus.PAUSING.equals(dispatch.getStatus())
                    && !DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus()))) return false;
            if (recovery != null && recovery.cancelRequested(tenantId, dispatchId)) return false;
            if (!checkpointService.confirmPauseReceipt(tenantId, executorId, dispatchId, checkpointSeq, checkpointSha256)) return false;
            if (dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PAUSED,
                    null, null, null, null,
                    DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus()) ? "" : null,
                    dispatch.getVersion(), SYSTEM_USER_ID) != 1) throw new IllegalStateException("pause checkpoint transition must retry");
            if (recovery != null) recovery.recordPauseOutcome(dispatch, "CONFIRMED", null);
            return true;
        });
        if (paused && owned != null) completeScheduledCancelIfReady(owned);
        return paused;
    }

    /**
     * A successful task result can race with a user pause request. In that race the
     * pause intent wins: the durable result checkpoint becomes the pause boundary,
     * so comment rework can continue without losing the completed provider turn.
     */
    public CompletionDisposition onCompletedWhilePausing(long tenantId, long executorId,
            long dispatchId, long checkpointSeq, String checkpointSha256) {
        DispatchDO owned = ownedDispatch(tenantId, executorId, dispatchId);
        if (owned == null) {
            return CompletionDisposition.REJECTED;
        }
        if (!DispatchStatus.PAUSING.equals(owned.getStatus())
                && !DispatchStatus.PAUSE_FAILED.equals(owned.getStatus())) {
            return CompletionDisposition.NOT_PAUSING;
        }
        CompletionDisposition disposition = inTransaction(() -> {
            if (recovery != null) recovery.lockWorkitemControl(owned);
            DispatchDO dispatch = dispatchDao.findById(dispatchId);
            if (dispatch == null || (!DispatchStatus.PAUSING.equals(dispatch.getStatus())
                    && !DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus()))) {
                return CompletionDisposition.NOT_PAUSING;
            }
            if (recovery != null && recovery.cancelRequested(tenantId, dispatchId)) {
                return CompletionDisposition.REJECTED;
            }
            if (!checkpointService.confirmPauseReceipt(tenantId, executorId, dispatchId, checkpointSeq, checkpointSha256)) {
                return CompletionDisposition.REJECTED;
            }
            if (dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PAUSED,
                    null, null, null, null, null, dispatch.getVersion(), SYSTEM_USER_ID) != 1) {
                throw new IllegalStateException("completion pause checkpoint transition must retry");
            }
            if (recovery != null) recovery.recordPauseOutcome(dispatch, "CONFIRMED", "COMPLETED_WHILE_PAUSING");
            return CompletionDisposition.PAUSED;
        });
        if (disposition == CompletionDisposition.PAUSED) completeScheduledCancelIfReady(owned);
        return disposition;
    }

    public boolean onPauseFailed(long tenantId, long executorId, long dispatchId, String error) {
        DispatchDO owned = ownedDispatch(tenantId, executorId, dispatchId);
        if (owned == null || !DispatchStatus.PAUSING.equals(owned.getStatus())) {
            return false;
        }
        return inTransaction(() -> {
            if (recovery != null) recovery.lockWorkitemControl(owned);
            DispatchDO dispatch = dispatchDao.findById(dispatchId);
            if (dispatch == null || !DispatchStatus.PAUSING.equals(dispatch.getStatus())) return false;
            if (recovery != null && recovery.cancelRequested(tenantId, dispatchId)) return false;
            if (dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PAUSE_FAILED,
                    null, null, null, null, error, dispatch.getVersion(), SYSTEM_USER_ID) != 1) return false;
            if (recovery != null) recovery.recordPauseOutcome(dispatch, "FAILED", error);
            return true;
        });
    }

    /**
     * Closes a lost pause handshake without guessing that a durable checkpoint exists.
     * The optimistic transition is harmless when a late PAUSED/FAILED response already won.
     */
    public boolean expireTimedOutPause(DispatchDO stale, long beforeEpochMillis) {
        if (stale == null || stale.getId() == null) {
            return false;
        }
        return inTransaction(() -> {
            if (recovery != null) recovery.lockWorkitemControl(stale);
            if (recovery != null && recovery.cancelRequested(stale.getTenantId(), stale.getId())) return false;
            String error = DispatchFailureReason.PAUSE_CONFIRMATION_MISSING
                    + ": 暂停确认超时，平台未收到有效暂停检查点";
            if (dispatchDao.failStalePausing(stale.getId(), stale.getTenantId(), beforeEpochMillis,
                    error, SYSTEM_USER_ID) != 1) return false;
            if (recovery != null) recovery.recordPauseOutcome(stale, "FAILED", error);
            return true;
        });
    }

    private DispatchDO requireDispatch(long tenantId, long workitemId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() != tenantId
                || dispatch.executionSourceType() != ExecutionSourceType.WORKITEM
                || dispatch.getWorkitemId() != workitemId) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        return dispatch;
    }

    private void completeScheduledCancelIfReady(DispatchDO dispatch) {
        if (scheduledRunDao == null || scheduledRunService == null
                || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN) return;
        ScheduledTaskRunDO run = scheduledRunDao.findById(dispatch.getTenantId(), dispatch.getWorkitemId());
        if (run == null || !"CANCEL_PENDING".equals(run.getError())) return;
        boolean quiescent = dispatchDao.listBySource(dispatch.getTenantId(), ExecutionSourceType.SCHEDULED_TASK_RUN.name(), dispatch.getWorkitemId())
                .stream().allMatch(d -> DispatchStatus.isTerminal(d.getStatus()) || DispatchStatus.PAUSED.equals(d.getStatus()));
        if (quiescent) scheduledRunService.finish(run, "CANCELED", null, "CANCELED", SYSTEM_USER_ID);
    }

    private DispatchDO ownedDispatch(long tenantId, long executorId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() != tenantId
                || dispatch.getExecutorId() == null || dispatch.getExecutorId() != executorId) {
            return null;
        }
        return dispatch;
    }

    private void markPauseFailedAfterSendFailure(DispatchDO dispatch, long tenantId,
            long userId, RuntimeException sendFailed) {
        String message = sendFailed.getMessage();
        if (message == null || message.isBlank()) {
            message = "暂停请求发送失败，请重试暂停";
        }
        String finalMessage = message;
        inTransaction(() -> {
            if (recovery != null) recovery.lockWorkitemControl(dispatch);
            int rows = dispatchDao.updateStatus(dispatch.getId(), tenantId, DispatchStatus.PAUSE_FAILED,
                    null, null, null, null, finalMessage, dispatch.getVersion(), userId);
            if (rows == 1) {
                dispatch.setStatus(DispatchStatus.PAUSE_FAILED);
                dispatch.setError(finalMessage);
                dispatch.setVersion(dispatch.getVersion() + 1);
                if (recovery != null) recovery.recordPauseOutcome(dispatch, "FAILED", finalMessage);
            }
            return rows;
        });
    }
}
