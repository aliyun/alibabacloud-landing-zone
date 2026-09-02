package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.DispatchCheckpointService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/**
 * Computes the only safe cross-Run continuation: a continuous Run may reuse a
 * provider session solely on the executor which created it. Once affinity has
 * expired, a durable checkpoint remains usable but its native session is
 * intentionally discarded.
 */
@Service
public class ScheduledTaskRunRecoveryService {
    static final String SOURCE_EXECUTOR_TIMEOUT = "SOURCE_EXECUTOR_TIMEOUT";
    private final ScheduledTaskRunDao runDao;
    private final DispatchDao dispatchDao;
    private final ExecutorSelector executorSelector;
    private final DispatchService dispatchService;
    private final Clock clock;
    private DispatchCheckpointService checkpointService;
    private NotifyService notifyService;
    private ScheduledTaskMetrics metrics;
    private ScheduledTaskRunService runService;
    private ScheduledTaskNotificationService scheduledTaskNotificationService;

    @Autowired
    public ScheduledTaskRunRecoveryService(ScheduledTaskRunDao runDao, DispatchDao dispatchDao,
            ExecutorSelector executorSelector, DispatchService dispatchService) {
        this(runDao, dispatchDao, executorSelector, dispatchService, Clock.systemUTC());
    }

    ScheduledTaskRunRecoveryService(ScheduledTaskRunDao runDao, DispatchDao dispatchDao,
            ExecutorSelector executorSelector, DispatchService dispatchService, Clock clock) {
        this.runDao = runDao;
        this.dispatchDao = dispatchDao;
        this.executorSelector = executorSelector;
        this.dispatchService = dispatchService;
        this.clock = clock;
        this.runService = new ScheduledTaskRunService(runDao);
    }
    @Autowired(required = false)
    public void setNotifyService(NotifyService notifyService) { this.notifyService = notifyService; }
    @Autowired(required = false)
    public void setMetrics(ScheduledTaskMetrics metrics) { this.metrics = metrics; }
    @Autowired public void setRunService(ScheduledTaskRunService runService) { this.runService = runService; }
    @Autowired(required = false)
    public void setScheduledTaskNotificationService(ScheduledTaskNotificationService service) { this.scheduledTaskNotificationService = service; }
    @Autowired(required = false)
    public void setCheckpointService(DispatchCheckpointService checkpointService) { this.checkpointService = checkpointService; }

    /** Reconcile an unstarted Run. It is safe to call repeatedly from compensation. */
    public ResumePlan reconcile(ScheduledTaskRunDO run) {
        if (bridgeRunning(run)) return ResumePlan.none();
        ResumePlan plan = plan(run);
        if (plan.state() == State.AFFINE && !java.util.Objects.equals(run.getResumeFromRunId(), plan.sourceRunId())) {
            runDao.markResumeSource(run.getWorkspaceId(), run.getId(), plan.sourceRunId(), run.getVersion(), 0L);
        }
        if (plan.degraded() && !(Integer.valueOf(1).equals(run.getDegradedResume())
                && SOURCE_EXECUTOR_TIMEOUT.equals(run.getDegradedReason())
                && java.util.Objects.equals(run.getResumeFromRunId(), plan.sourceRunId()))) {
            if (!dispatchService.fencePendingContinuousResume(run.getWorkspaceId(), run.getId())) {
                return ResumePlan.waitForSource(plan.sourceRunId(), plan.sourceDispatch());
            }
            dispatchService.degradeResume(run.getId(), plan.sourceDispatch().getId(),
                    SOURCE_EXECUTOR_TIMEOUT, run.getWorkspaceId());
            runDao.markDegraded(run.getWorkspaceId(), run.getId(), SOURCE_EXECUTOR_TIMEOUT,
                    plan.sourceRunId(), run.getVersion(), 0L);
            notifyDegraded(run);
            if (metrics != null) metrics.degraded(SOURCE_EXECUTOR_TIMEOUT);
        }
        return plan;
    }

    /** Repairs a lost Run status CAS from the authoritative source Dispatch. */
    private boolean bridgeRunning(ScheduledTaskRunDO run) {
        if (run == null || !"WAITING_EXECUTOR".equals(run.getStatus()) || run.getWorkspaceId() == null || run.getId() == null) return false;
        List<DispatchDO> dispatches = dispatchDao.listBySource(run.getWorkspaceId(),
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), run.getId());
        boolean running = dispatches != null && dispatches.stream()
                .anyMatch(d -> d != null && DispatchStatus.RUNNING.equals(d.getStatus()));
        return running && runService.transitionSystem(run, "WAITING_EXECUTOR", "RUNNING", 0L);
    }

    /**
     * Return WAIT while the prior executor is still within the affinity window;
     * the caller must not enqueue any Dispatch in that state.
     */
    public ResumePlan plan(ScheduledTaskRunDO run) {
        if (run == null || !"CONTINUOUS".equals(sessionMode(run)) || run.getId() == null
                || run.getWorkspaceId() == null || run.getScheduledTaskId() == null) {
            return ResumePlan.none();
        }
        ScheduledTaskRunDO sourceRun = latestTerminalPredecessor(run);
        if (sourceRun == null) return ResumePlan.none();
        Long assignedAgent = run.getCurrentAgentId() == null ? run.getInitialAgentId() : run.getCurrentAgentId();
        long agentId = assignedAgent == null ? 0L : assignedAgent;
        if (agentId <= 0) return ResumePlan.none();
        DispatchDO source = latestDispatch(sourceRun, agentId);
        if (source == null || source.getId() == null || source.getExecutorId() == null) return ResumePlan.none();
        if (!matchesFrozenVersion(run, agentId, source)) return ResumePlan.none();
        if (executorSelector.isAvailable(source.getExecutorId())) {
            return ResumePlan.affine(sourceRun.getId(), source);
        }
        long elapsedMillis = Math.max(0L, clock.millis() - createdAt(run));
        long timeoutMillis = affinityTimeoutSeconds(run) * 1000L;
        if (elapsedMillis < timeoutMillis) return ResumePlan.waitForSource(sourceRun.getId(), source);
        return ResumePlan.degraded(sourceRun.getId(), source);
    }

    private ScheduledTaskRunDO latestTerminalPredecessor(ScheduledTaskRunDO run) {
        List<ScheduledTaskRunDO> candidates = runDao.listByTask(run.getWorkspaceId(), run.getScheduledTaskId(), 200, 0);
        if (candidates == null) return null;
        return candidates.stream().filter(candidate -> candidate != null && candidate.getId() != null
                        && candidate.getId() < run.getId() && isTerminal(candidate.getStatus()))
                .max(Comparator.comparing(ScheduledTaskRunDO::getId)).orElse(null);
    }

    private DispatchDO latestDispatch(ScheduledTaskRunDO sourceRun, long agentId) {
        List<DispatchDO> rows = dispatchDao.listLatestBySourceAndAgent(sourceRun.getWorkspaceId(),
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), sourceRun.getId(), agentId, 20);
        if (rows == null) return null;
        return rows.stream().filter(d -> d != null && d.getId() != null && d.getExecutorId() != null
                        && checkpointService != null
                        && checkpointService.hasResumableSession(sourceRun.getWorkspaceId(), d.getId()))
                .max(Comparator.comparing(DispatchDO::getId)).orElse(null);
    }

    private static boolean matchesFrozenVersion(ScheduledTaskRunDO run, long agentId, DispatchDO source) {
        try {
            JSONObject snapshot = JSON.parseObject(run.getExecutionSnapshotJson());
            for (Object item : snapshot.getJSONArray("agentContexts")) {
                JSONObject context = (JSONObject) item;
                if (context.getLongValue("agentId") == agentId) return source.getAgentVersionId() != null
                        && source.getAgentVersionId().longValue() == context.getLongValue("agentVersionId");
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static String sessionMode(ScheduledTaskRunDO run) {
        try { JSONObject policies = JSON.parseObject(run.getExecutionSnapshotJson()).getJSONObject("policies");
            return policies == null ? run.getSessionMode() : policies.getString("sessionMode"); }
        catch (RuntimeException ignored) { return run.getSessionMode(); }
    }
    private static long affinityTimeoutSeconds(ScheduledTaskRunDO run) {
        try { JSONObject policies = JSON.parseObject(run.getExecutionSnapshotJson()).getJSONObject("policies");
            Long value = policies == null ? null : policies.getLong("affinityTimeoutSeconds");
            return value == null || value <= 0 ? 1800L : value; }
        catch (RuntimeException ignored) { return 1800L; }
    }
    private static long createdAt(ScheduledTaskRunDO run) {
        return run.getGmtCreate() != null ? run.getGmtCreate().getTime()
                : run.getScheduledAt() != null ? run.getScheduledAt().getTime() : 0L;
    }
    private static boolean isTerminal(String status) {
        try { return ScheduledTaskRunStatus.valueOf(status).isTerminal(); }
        catch (RuntimeException ignored) { return false; }
    }
    private void notifyDegraded(ScheduledTaskRunDO run) {
        if (scheduledTaskNotificationService != null) {
            scheduledTaskNotificationService.status(run, SOURCE_EXECUTOR_TIMEOUT);
            return;
        }
        if (notifyService == null || run.getOwnerId() == null) return;
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(run.getWorkspaceId()); event.setType("SCHEDULED_TASK_RESUME_DEGRADED");
        event.setTitle("定时任务会话已降级恢复"); event.setContent(SOURCE_EXECUTOR_TIMEOUT);
        event.setRefType("SCHEDULED_TASK_RUN"); event.setRefId(run.getId());
        event.setRecipientIds(List.of(run.getOwnerId())); notifyService.notify(event);
    }

    public record ResumePlan(Long sourceRunId, DispatchDO sourceDispatch, State state) {
        static ResumePlan none() { return new ResumePlan(null, null, State.NONE); }
        static ResumePlan affine(Long sourceRunId, DispatchDO source) { return new ResumePlan(sourceRunId, source, State.AFFINE); }
        static ResumePlan waitForSource(Long sourceRunId, DispatchDO source) { return new ResumePlan(sourceRunId, source, State.WAIT); }
        static ResumePlan degraded(Long sourceRunId, DispatchDO source) { return new ResumePlan(sourceRunId, source, State.DEGRADED); }
        public boolean degraded() { return state == State.DEGRADED; }
        public boolean waitsForSource() { return state == State.WAIT; }
        public boolean reusable() { return state == State.AFFINE || state == State.DEGRADED; }
    }
    public enum State { NONE, AFFINE, WAIT, DEGRADED }
}
