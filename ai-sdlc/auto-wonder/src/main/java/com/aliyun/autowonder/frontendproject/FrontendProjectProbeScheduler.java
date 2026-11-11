package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Drives durable frontend-project probes: claims QUEUED runs and executes them, and sweeps runs
 * stuck in a non-terminal state (a worker that crashed mid-checkout).
 *
 * <p>Reuses the platform's existing durable-async pattern — a DB status column plus a
 * {@code @Scheduled} sweep guarded by a Redis lock — exactly like {@code DispatchCompensationTask}
 * and {@code AiCompensationTask}. Probing is not a delivery, so it must not borrow the dispatch
 * state machine; claim safety comes from the {@code updateRunning} CAS in the service.
 */
public class FrontendProjectProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(FrontendProjectProbeScheduler.class);

    private static final String POLL_LOCK_KEY = "frontend-project:probe:poll-lock";
    private static final String SWEEP_LOCK_KEY = "frontend-project:probe:sweep-lock";
    private static final long LOCK_TTL_MS = 120_000L;
    private static final int POLL_BATCH = 10;
    private static final int SWEEP_BATCH = 100;
    private static final long RUNNING_STUCK_MS = 10 * 60_000L;

    private final FrontendProjectProbeDao probeDao;
    private final FrontendProjectProbeService probeService;
    private final RedisManager redisManager;

    public FrontendProjectProbeScheduler(FrontendProjectProbeDao probeDao,
                                         FrontendProjectProbeService probeService,
                                         RedisManager redisManager) {
        this.probeDao = probeDao;
        this.probeService = probeService;
        this.redisManager = redisManager;
    }

    @Scheduled(fixedDelayString = "${autowonder.frontend-project.probe.fixed-delay-ms:5000}")
    public void poll() {
        String lockOwner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(POLL_LOCK_KEY, lockOwner, LOCK_TTL_MS)) {
            return;
        }
        try {
            List<FrontendProjectProbeDO> queued =
                    probeDao.listByStatus(ProbeStatus.QUEUED, POLL_BATCH);
            if (queued == null || queued.isEmpty()) {
                return;
            }
            for (FrontendProjectProbeDO probe : queued) {
                try {
                    probeService.executeProbe(probe);
                } catch (Exception e) {
                    log.error("frontend probe poll execute error probeId={}", probe.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("frontend probe poll error", e);
        } finally {
            redisManager.releaseLock(POLL_LOCK_KEY, lockOwner);
        }
    }

    @Scheduled(fixedDelayString = "${autowonder.frontend-project.probe.sweep-delay-ms:60000}")
    public void sweepStuck() {
        String lockOwner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(SWEEP_LOCK_KEY, lockOwner, LOCK_TTL_MS)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            List<FrontendProjectProbeDO> stuck = probeDao.listStuck(
                    List.of(ProbeStatus.QUEUED, ProbeStatus.RUNNING), now - RUNNING_STUCK_MS, SWEEP_BATCH);
            if (stuck == null || stuck.isEmpty()) {
                return;
            }
            for (FrontendProjectProbeDO probe : stuck) {
                // A QUEUED run that never got claimed can be retried by re-driving it; a RUNNING
                // run whose node is gone is failed so the user sees a clear error and retry entry.
                if (ProbeStatus.QUEUED.equals(probe.getStatus())) {
                    probeService.executeProbe(probe);
                } else {
                    int updated = probeDao.updateFailed(probe.getId(), probe.getWorkspaceId(),
                            "probe stuck (node may have crashed)", probe.getVersion());
                    if (updated > 0) {
                        log.warn("frontend probe swept as stuck probeId={}", probe.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("frontend probe sweep error", e);
        } finally {
            redisManager.releaseLock(SWEEP_LOCK_KEY, lockOwner);
        }
    }
}
