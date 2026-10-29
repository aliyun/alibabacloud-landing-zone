package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Stops the work a logically deleted workspace still owns (F2/D5).
 *
 * Deliberately split in two. {@link #pauseScheduledTasks} is a single SQL statement and joins the
 * delete transaction, so a workspace can never end up deleted while its timers are still armed.
 * {@link #stopInFlightDispatches} reaches executors through {@code DispatchControlTransport}; a
 * remote round trip inside a DB transaction would hold row locks for its whole duration, so it runs
 * after commit as the compensating half of the same linkage.
 */
@Component
public class WorkspaceDeletionLinkage {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDeletionLinkage.class);

    /** Shown to operators and written to dispatch.error, per the delete-confirm copy (F2). */
    public static final String DELETION_REASON = "工作空间已删除";

    private static final int DISPATCH_LINKAGE_LIMIT = 500;

    /**
     * Not yet handed to an executor, so there is nothing to ask to pause — cancel outright.
     * Everything else fetched by {@link #IN_FLIGHT_STATUSES} goes through DispatchPauseService,
     * which already accepts DISPATCHED/ACKED/RUNNING/PAUSING/PAUSE_FAILED.
     */
    private static final Set<String> CANCELABLE_STATUSES =
            Set.of(DispatchStatus.PENDING, DispatchStatus.PACKAGING, DispatchStatus.WAITING_FOR_PAUSE);

    private static final List<String> IN_FLIGHT_STATUSES = List.of(
            DispatchStatus.PENDING,
            DispatchStatus.PACKAGING,
            DispatchStatus.DISPATCHED,
            DispatchStatus.ACKED,
            DispatchStatus.RUNNING,
            DispatchStatus.PAUSING,
            DispatchStatus.PAUSE_FAILED,
            DispatchStatus.WAITING_FOR_PAUSE);

    private final ScheduledTaskDao scheduledTaskDao;
    private final DispatchDao dispatchDao;
    private final DispatchPauseService dispatchPauseService;
    private final JdbcTemplate jdbc;

    public WorkspaceDeletionLinkage(ScheduledTaskDao scheduledTaskDao,
                                    DispatchDao dispatchDao,
                                    DispatchPauseService dispatchPauseService,
                                    JdbcTemplate jdbc) {
        this.scheduledTaskDao = scheduledTaskDao;
        this.dispatchDao = dispatchDao;
        this.dispatchPauseService = dispatchPauseService;
        this.jdbc = jdbc;
    }

    /** In-transaction half: bulk-pauses every ACTIVE scheduled task of the workspace. */
    public int pauseScheduledTasks(long workspaceId, long operatorId) {
        int paused = scheduledTaskDao.pauseActiveByWorkspace(workspaceId, operatorId);
        if (paused > 0) {
            log.info("Paused {} scheduled task(s) of workspace {}: {}", paused, workspaceId, DELETION_REASON);
        }
        return paused;
    }

    /**
     * In-transaction half: stops the platform shared bots of the deleted workspace from routing
     * messages into it. Only SHARED bindings are touched — a LEGACY binding owns its own
     * credentials and is deliberately left for an explicit operator decision.
     */
    public int disableSharedBindings(long workspaceId, long operatorId) {
        int disabled = jdbc.update(
                "UPDATE feishu_robot_binding SET status='DISABLED',route_version=route_version+1,"
                        + "modifier_id=?,version=version+1 "
                        + "WHERE tenant_id=? AND binding_mode='SHARED' AND status<>'DISABLED'",
                operatorId, workspaceId);
        disabled += jdbc.update(
                "UPDATE dingtalk_robot_binding SET status='DISABLED',route_version=route_version+1,"
                        + "modifier_id=?,version=version+1 "
                        + "WHERE tenant_id=? AND binding_mode='SHARED' AND status<>'DISABLED' "
                        + "AND is_deleted=0",
                operatorId, workspaceId);
        if (disabled > 0) {
            log.info("Disabled {} shared binding(s) of workspace {}: {}",
                    disabled, workspaceId, DELETION_REASON);
        }
        return disabled;
    }

    /**
     * Post-commit half: pauses or cancels in-flight dispatches. Returns how many were stopped.
     * A failure on one dispatch never aborts the others and never undoes the delete — the workspace
     * is already gone, so a leftover is logged and left to the stuck-dispatch compensation.
     */
    public int stopInFlightDispatches(long workspaceId, long operatorId) {
        List<DispatchDO> inFlight = dispatchDao.listInFlightByTenant(
                workspaceId, IN_FLIGHT_STATUSES, DISPATCH_LINKAGE_LIMIT);
        int stopped = 0;
        for (DispatchDO dispatch : inFlight) {
            if (stop(dispatch, operatorId)) {
                stopped++;
            }
        }
        if (stopped < inFlight.size()) {
            log.warn("Stopped {} of {} in-flight dispatch(es) of deleted workspace {}",
                    stopped, inFlight.size(), workspaceId);
        }
        return stopped;
    }

    private boolean stop(DispatchDO dispatch, long operatorId) {
        try {
            if (CANCELABLE_STATUSES.contains(dispatch.getStatus())) {
                return cancel(dispatch, operatorId);
            }
            requestPause(dispatch, operatorId);
            return true;
        } catch (RuntimeException failure) {
            log.warn("Failed to stop dispatch {} of deleted workspace {}",
                    dispatch.getId(), dispatch.getTenantId(), failure);
            return false;
        }
    }

    private void requestPause(DispatchDO dispatch, long operatorId) {
        if (dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            dispatchPauseService.requestPauseScheduledRun(
                    dispatch.getTenantId(), dispatch.getWorkitemId(), dispatch.getId(), operatorId);
        } else {
            dispatchPauseService.requestPause(
                    dispatch.getTenantId(), dispatch.getWorkitemId(), dispatch.getId(), operatorId);
        }
    }

    private boolean cancel(DispatchDO dispatch, long operatorId) {
        return dispatchDao.updateStatus(dispatch.getId(), dispatch.getTenantId(),
                DispatchStatus.CANCELED, null, null, null, null, DELETION_REASON,
                dispatch.getVersion(), operatorId) == 1;
    }
}
