package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

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

    public DispatchPauseService(DispatchDao dispatchDao,
            DispatchCheckpointService checkpointService,
            DispatchControlTransport transport) {
        this.dispatchDao = dispatchDao;
        this.checkpointService = checkpointService;
        this.transport = transport;
    }

    public DispatchDO requestPause(long tenantId, long workitemId, long dispatchId, long userId) {
        DispatchDO dispatch = requireDispatch(tenantId, workitemId, dispatchId);
        if (DispatchStatus.PAUSED.equals(dispatch.getStatus())) {
            return dispatch;
        }
        if (DispatchStatus.PAUSING.equals(dispatch.getStatus())) {
            transport.pause(dispatch);
            return dispatch;
        }
        if (!DispatchStatus.isPauseable(dispatch.getStatus())
                && !DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "当前执行状态不能暂停");
        }
        String clearedError = DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus()) ? "" : null;
        int rows = dispatchDao.updateStatus(dispatch.getId(), tenantId, DispatchStatus.PAUSING,
                null, null, null, null, clearedError, dispatch.getVersion(), userId);
        if (rows != 1) {
            throw new BizException(ErrorCode.CONFLICT, "执行状态已变化，请刷新后重试");
        }
        dispatch.setStatus(DispatchStatus.PAUSING);
        dispatch.setError(null);
        dispatch.setVersion(dispatch.getVersion() + 1);
        try {
            transport.pause(dispatch);
        } catch (RuntimeException sendFailed) {
            markPauseFailedAfterSendFailure(dispatch, tenantId, userId, sendFailed);
            throw sendFailed;
        }
        return dispatch;
    }

    public boolean onPaused(long tenantId, long executorId, long dispatchId,
            long checkpointSeq, String checkpointSha256) {
        DispatchDO dispatch = ownedDispatch(tenantId, executorId, dispatchId);
        if (dispatch == null) {
            return false;
        }
        if (DispatchStatus.PAUSED.equals(dispatch.getStatus())) {
            return true;
        }
        if (!DispatchStatus.PAUSING.equals(dispatch.getStatus())
                || !checkpointService.matchesDurableReceipt(
                        tenantId, dispatchId, checkpointSeq, checkpointSha256)) {
            return false;
        }
        return dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PAUSED,
                null, null, null, null, null, dispatch.getVersion(), SYSTEM_USER_ID) == 1;
    }

    /**
     * A successful task result can race with a user pause request. In that race the
     * pause intent wins: the durable result checkpoint becomes the pause boundary,
     * so comment rework can continue without losing the completed provider turn.
     */
    public CompletionDisposition onCompletedWhilePausing(long tenantId, long executorId,
            long dispatchId, long checkpointSeq, String checkpointSha256) {
        DispatchDO dispatch = ownedDispatch(tenantId, executorId, dispatchId);
        if (dispatch == null) {
            return CompletionDisposition.REJECTED;
        }
        if (!DispatchStatus.PAUSING.equals(dispatch.getStatus())
                && !DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus())) {
            return CompletionDisposition.NOT_PAUSING;
        }
        if (!checkpointService.matchesDurableReceipt(
                tenantId, dispatchId, checkpointSeq, checkpointSha256)) {
            return CompletionDisposition.REJECTED;
        }
        int rows = dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PAUSED,
                null, null, null, null, null, dispatch.getVersion(), SYSTEM_USER_ID);
        return rows == 1 ? CompletionDisposition.PAUSED : CompletionDisposition.REJECTED;
    }

    public boolean onPauseFailed(long tenantId, long executorId, long dispatchId, String error) {
        DispatchDO dispatch = ownedDispatch(tenantId, executorId, dispatchId);
        if (dispatch == null || !DispatchStatus.PAUSING.equals(dispatch.getStatus())) {
            return false;
        }
        return dispatchDao.updateStatus(dispatchId, tenantId, DispatchStatus.PAUSE_FAILED,
                null, null, null, null, error, dispatch.getVersion(), SYSTEM_USER_ID) == 1;
    }

    /**
     * Closes a lost pause handshake without guessing that a durable checkpoint exists.
     * The optimistic transition is harmless when a late PAUSED/FAILED response already won.
     */
    public boolean expireTimedOutPause(DispatchDO stale, long beforeEpochMillis) {
        if (stale == null || stale.getId() == null) {
            return false;
        }
        return dispatchDao.failStalePausing(stale.getId(), stale.getTenantId(), beforeEpochMillis,
                "暂停确认超时，请重试暂停；若执行器已离线，可选择继续恢复",
                SYSTEM_USER_ID) == 1;
    }

    private DispatchDO requireDispatch(long tenantId, long workitemId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() != tenantId
                || dispatch.getWorkitemId() != workitemId) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        return dispatch;
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
        int rows = dispatchDao.updateStatus(dispatch.getId(), tenantId, DispatchStatus.PAUSE_FAILED,
                null, null, null, null, message, dispatch.getVersion(), userId);
        if (rows == 1) {
            dispatch.setStatus(DispatchStatus.PAUSE_FAILED);
            dispatch.setError(message);
            dispatch.setVersion(dispatch.getVersion() + 1);
        }
    }
}
