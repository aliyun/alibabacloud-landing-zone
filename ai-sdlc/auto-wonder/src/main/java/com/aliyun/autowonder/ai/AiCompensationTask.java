package com.aliyun.autowonder.ai;

import com.aliyun.autowonder.ai.engine.AiStreamPublisher;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class AiCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(AiCompensationTask.class);

    private static final String LOCK_KEY = "ai:compensation:lock";
    private static final long LOCK_TTL_MS = 60_000L;
    private static final int BATCH = 100;
    private static final long RUNNING_STUCK_MS = 10 * 60_000L;

    private final AiSessionDao sessionDao;
    private final AiStreamPublisher streamPublisher;
    private final RedisManager redisManager;
    private final RepoDao repoDao;

    public AiCompensationTask(AiSessionDao sessionDao, AiStreamPublisher streamPublisher,
            RedisManager redisManager, RepoDao repoDao) {
        this.sessionDao = sessionDao;
        this.streamPublisher = streamPublisher;
        this.redisManager = redisManager;
        this.repoDao = repoDao;
    }

    @Scheduled(fixedDelayString = "${autowonder.ai.compensation.fixed-delay-ms:60000}")
    public void sweep() {
        String lockOwner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(LOCK_KEY, lockOwner, LOCK_TTL_MS)) {
            return;
        }
        log.info("ai compensation sweep started");
        try {
            long now = System.currentTimeMillis();
            List<AiSessionDO> stuck = sessionDao.listStuck(
                    List.of(AiConstants.Status.RUNNING), now - RUNNING_STUCK_MS, BATCH);
            if (stuck == null) return;
            log.info("ai compensation found stuck={}", stuck.size());

            for (AiSessionDO s : stuck) {
                int updated = sessionDao.updateFailed(s.getId(), s.getTenantId(),
                        "session stuck (node may have crashed)", s.getVersion());
                if (updated > 0) {
                    markRepoScanFailedIfNeeded(s);
                    streamPublisher.publishStatus(s.getId(), s.getTenantId(), AiConstants.Status.FAILED);
                    log.info("ai compensation: marked stuck session FAILED id={}", s.getId());
                }
            }
        } catch (Exception e) {
            log.error("ai compensation sweep error", e);
        } finally {
            redisManager.releaseLock(LOCK_KEY, lockOwner);
        }
    }

    private void markRepoScanFailedIfNeeded(AiSessionDO session) {
        if (!AiConstants.Scene.REPO_SCAN.equals(session.getScene())
                || !"REPO".equals(session.getBizRefType())
                || session.getBizRefId() == null) {
            return;
        }
        RepoDO repo = repoDao.findById(session.getBizRefId());
        if (repo == null) {
            return;
        }
        repoDao.updateScanStatus(repo.getId(), session.getTenantId(), "FAILED",
                repo.getVersion(), null);
    }
}
