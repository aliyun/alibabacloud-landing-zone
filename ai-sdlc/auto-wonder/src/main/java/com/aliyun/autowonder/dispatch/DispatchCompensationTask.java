package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DispatchCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(DispatchCompensationTask.class);

    private static final String LOCK_KEY = "dispatch:compensation:lock";
    private static final long LOCK_TTL_MS = 60_000L;
    private static final int BATCH = 200;
    private static final long PENDING_STUCK_MS = 60_000L;        // PENDING older than 1m -> re-drive
    private static final long PACKAGING_STUCK_MS = 5 * 60_000L;  // allow normal package/OSS latency before recovery
    private static final long UNACKNOWLEDGED_STUCK_MS = 2 * 60_000L;
    private static final long PAUSING_STUCK_MS = 2 * 60_000L;
    private static final long INFLIGHT_STUCK_MS = 60 * 60_000L;  // ACKED/RUNNING older than 60m

    private static final List<String> PENDING_STATES =
            List.of(DispatchStatus.PENDING);
    private static final List<String> PACKAGING_STATES =
            List.of(DispatchStatus.PACKAGING);
    private static final List<String> UNACKNOWLEDGED_STATES =
            List.of(DispatchStatus.DISPATCHED);
    private static final List<String> PAUSING_STATES =
            List.of(DispatchStatus.PAUSING, DispatchStatus.PAUSE_FAILED);
    private static final List<String> INFLIGHT_STATES =
            List.of(DispatchStatus.ACKED, DispatchStatus.RUNNING);

    private final DispatchDao dispatchDao;
    private final DispatchService dispatchService;
    private final DispatchPauseService pauseService;
    private final InteractionWorkflowService interactionWorkflowService;
    private final RedisManager redisManager;

    public DispatchCompensationTask(DispatchDao dispatchDao, DispatchService dispatchService,
            DispatchPauseService pauseService, InteractionWorkflowService interactionWorkflowService,
            RedisManager redisManager) {
        this.dispatchDao = dispatchDao;
        this.dispatchService = dispatchService;
        this.pauseService = pauseService;
        this.interactionWorkflowService = interactionWorkflowService;
        this.redisManager = redisManager;
    }

    @Scheduled(fixedDelayString = "${autowonder.dispatch.compensation.fixed-delay-ms:30000}")
    public void sweep() {
        String lockOwner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(LOCK_KEY, lockOwner, LOCK_TTL_MS)) {
            return;
        }
        try {
            log.info("compensation sweep started");
            long now = System.currentTimeMillis();
            List<DispatchDO> pending = safe(dispatchDao.listStuck(PENDING_STATES,
                    now - PENDING_STUCK_MS, BATCH));
            List<DispatchDO> packaging = safe(dispatchDao.listStuck(PACKAGING_STATES,
                    now - PACKAGING_STUCK_MS, BATCH));
            List<DispatchDO> unacknowledged = safe(dispatchDao.listStuck(UNACKNOWLEDGED_STATES,
                    now - UNACKNOWLEDGED_STUCK_MS, BATCH));
            long pausingCutoff = now - PAUSING_STUCK_MS;
            List<DispatchDO> pausing = safe(dispatchDao.listStuck(PAUSING_STATES,
                    pausingCutoff, BATCH));
            List<DispatchDO> inflight = safe(dispatchDao.listStuck(INFLIGHT_STATES,
                    now - INFLIGHT_STUCK_MS, BATCH));
            log.info("compensation found pending={} packaging={} unacknowledged={} pausing={} inflight={}",
                    pending.size(), packaging.size(), unacknowledged.size(), pausing.size(), inflight.size());
            // PENDING stuck -> re-drive (runPending only accepts PENDING rows)
            for (DispatchDO d : pending) {
                try {
                    dispatchService.runPending(d.getId());
                } catch (Exception e) {
                    log.warn("compensation re-drive failed dispatchId={}", d.getId(), e);
                }
            }
            // A stale packaging worker is fenced by the optimistic version update. Its
            // eventual DISPATCHED transition loses the race, while the row can be retried.
            for (DispatchDO d : packaging) {
                try {
                    dispatchService.returnPackagingToPending(d.getTenantId(), d.getId());
                } catch (Exception e) {
                    log.warn("compensation packaging-requeue failed dispatchId={}", d.getId(), e);
                }
            }
            // DISPATCHED without ACK means delivery was not confirmed. Return it to the
            // durable queue instead of failing the workitem.
            for (DispatchDO d : unacknowledged) {
                try {
                    if (d.getExecutorId() != null) {
                        dispatchService.onBusy(d.getTenantId(), d.getExecutorId(), d.getId());
                    }
                } catch (Exception e) {
                    log.warn("compensation unacknowledged requeue failed dispatchId={}", d.getId(), e);
                }
            }
            // A pause command must eventually produce PAUSED or PAUSE_FAILED. If the
            // runtime disappears or loses the response, release the permanent PAUSING
            // dead zone so the user can explicitly retry pause or recover execution.
            for (DispatchDO d : pausing) {
                try {
                    boolean readyToFence = DispatchStatus.PAUSE_FAILED.equals(d.getStatus())
                            || pauseService.expireTimedOutPause(d, pausingCutoff);
                    if (readyToFence
                            && dispatchService.cancelPauseFailedIfExecutorReleased(d.getTenantId(), d.getId())) {
                        interactionWorkflowService.onPaused(d.getTenantId(), d.getId());
                    }
                } catch (Exception e) {
                    log.warn("compensation pause-expire failed dispatchId={}", d.getId(), e);
                }
            }
            for (DispatchDO d : inflight) {
                try {
                    dispatchService.onTimeout(d.getTenantId(), d.getId());
                } catch (Exception e) {
                    log.warn("compensation timeout failed dispatchId={}", d.getId(), e);
                }
            }
        } finally {
            redisManager.releaseLock(LOCK_KEY, lockOwner);
        }
    }

    private static List<DispatchDO> safe(List<DispatchDO> rows) {
        return rows == null ? List.of() : rows;
    }
}
