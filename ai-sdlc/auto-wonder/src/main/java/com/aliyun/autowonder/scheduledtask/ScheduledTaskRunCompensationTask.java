package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;

/** Cluster-safe reconciler for durable Run state; Dispatch delivery stays owned by DispatchCompensationTask. */
@Component
public class ScheduledTaskRunCompensationTask {
    private static final String LOCK_KEY = "scheduled-task-run:compensation:lock";
    private static final long LOCK_TTL_MS = 60_000L;
    private static final int BATCH = 200;
    private final ScheduledTaskRunDao runDao;
    private final ScheduledTaskRunOrchestrator orchestrator;
    private final ScheduledTaskRunRecoveryService recovery;
    private final ScheduledTaskRunService runService;
    private final RedisManager redis;
    private final ScheduledTaskCapabilityGuard capabilityGuard;

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduledTaskRunCompensationTask(ScheduledTaskRunDao runDao,
            ScheduledTaskRunOrchestrator orchestrator, ScheduledTaskRunRecoveryService recovery,
            RedisManager redis, ScheduledTaskRunService runService,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this.runDao=runDao; this.orchestrator=orchestrator; this.recovery=recovery; this.redis=redis;
        this.runService=runService; this.capabilityGuard=capabilityGuard;
    }

    @Scheduled(fixedDelayString = "${autowonder.scheduled-task.compensation.fixed-delay-ms:30000}")
    public void sweep() {
        if (!capabilityGuard.isAvailable()) return;
        String owner = UUID.randomUUID().toString();
        if (!redis.tryAcquireLock(LOCK_KEY, owner, LOCK_TTL_MS)) return;
        try {
            Date stale = new Date(System.currentTimeMillis() - 30_000L);
            for (ScheduledTaskRunDO run : safe(runDao.listStaleStarting(stale, BATCH))) {
                // Idempotent root keys make re-driving a process-crashed STARTING state safe.
                orchestrator.start(run.getWorkspaceId(), run.getId(), 0L);
            }
            for (ScheduledTaskRunDO run : safe(runDao.listStaleQueued(stale, BATCH))) {
                if (!mayStart(run)) continue;
                ScheduledTaskRunRecoveryService.ResumePlan plan = recovery.reconcile(run);
                if (plan.waitsForSource()) continue;
                // reconcile can persist affinity/degradation metadata, so never CAS using stale versions.
                ScheduledTaskRunDO current = runDao.findById(run.getWorkspaceId(), run.getId());
                if (current == null || current.getVersion() == null) continue;
                if ("WAITING_EXECUTOR".equals(current.getStatus())) {
                    if (!runService.transitionSystem(current, "WAITING_EXECUTOR", "QUEUED", 0L)) continue;
                }
                orchestrator.start(current.getWorkspaceId(), current.getId(), 0L);
            }
        } finally { redis.releaseLock(LOCK_KEY, owner); }
    }

    private boolean mayStart(ScheduledTaskRunDO run) {
        if (run == null || run.getWorkspaceId() == null || run.getScheduledTaskId() == null || run.getId() == null) return false;
        return safe(runDao.findActiveByTask(run.getWorkspaceId(), run.getScheduledTaskId())).stream()
                .noneMatch(other -> other != null && other.getId() != null && other.getId() < run.getId()
                        && !isTerminal(other.getStatus()));
    }
    private static boolean isTerminal(String status) {
        try { return ScheduledTaskRunStatus.valueOf(status).isTerminal(); }
        catch (RuntimeException ignored) { return false; }
    }
    private static List<ScheduledTaskRunDO> safe(List<ScheduledTaskRunDO> runs) { return runs == null ? List.of() : runs; }
}
