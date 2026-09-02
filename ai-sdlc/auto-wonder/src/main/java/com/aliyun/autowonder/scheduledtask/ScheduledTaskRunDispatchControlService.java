package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import org.springframework.stereotype.Service;
import java.util.List;

/** Safely controls only Dispatches whose durable source is the requested Run. */
@Service
public class ScheduledTaskRunDispatchControlService {
    private final DispatchDao dispatchDao;
    private final DispatchPauseService pauseService;
    public ScheduledTaskRunDispatchControlService(DispatchDao dispatchDao, DispatchPauseService pauseService) {
        this.dispatchDao = dispatchDao; this.pauseService = pauseService;
    }
    /** @return true when an executor must acknowledge a requested pause before terminal cancellation. */
    public boolean pauseActive(long workspaceId, long runId, long userId, boolean cancelPending) {
        boolean awaitingPause = false;
        List<DispatchDO> rows = dispatchDao.listBySource(workspaceId, ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId);
        if (rows == null) return false;
        for (DispatchDO dispatch : rows) {
            if (dispatch == null || DispatchStatus.isTerminal(dispatch.getStatus())) continue;
            if (DispatchStatus.PENDING.equals(dispatch.getStatus()) && cancelPending) {
                dispatchDao.updateStatus(dispatch.getId(), workspaceId, DispatchStatus.CANCELED,
                        null, null, null, null, "scheduled run canceled", dispatch.getVersion(), userId);
            } else if (DispatchStatus.isPauseable(dispatch.getStatus()) || DispatchStatus.PAUSE_FAILED.equals(dispatch.getStatus())) {
                pauseService.requestPauseScheduledRun(workspaceId, runId, dispatch.getId(), userId);
                awaitingPause = true;
            } else if (DispatchStatus.PAUSING.equals(dispatch.getStatus())) {
                awaitingPause = true;
            }
        }
        return awaitingPause;
    }
}
