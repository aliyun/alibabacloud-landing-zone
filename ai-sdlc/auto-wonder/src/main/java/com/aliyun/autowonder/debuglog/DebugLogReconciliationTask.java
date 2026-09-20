package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** debug_log PENDING 超 24h 对账兜底（设计文档 §4.7），集群内 Redis 锁保证单实例执行。 */
@Component
public class DebugLogReconciliationTask {

    private static final Logger log = LoggerFactory.getLogger(DebugLogReconciliationTask.class);
    private static final String LOCK_KEY = "debuglog:reconciliation:lock";
    private static final long LOCK_TTL_MS = 5 * 60_000L;

    private final DebugLogService debugLogService;
    private final RedisManager redisManager;

    public DebugLogReconciliationTask(DebugLogService debugLogService, RedisManager redisManager) {
        this.debugLogService = debugLogService;
        this.redisManager = redisManager;
    }

    @Scheduled(fixedDelayString = "${autowonder.debug-log.reconciliation.fixed-delay-ms:3600000}")
    public void reconcile() {
        String owner = UUID.randomUUID().toString();
        if (!redisManager.tryAcquireLock(LOCK_KEY, owner, LOCK_TTL_MS)) {
            return;
        }
        try {
            // sweep 汇总日志（含 scanned=0 的 idle 心跳）由 reconcilePendingOnce 自己打
            // （reason=DEBUG_LOG_RECONCILE_SWEEP，S11 评审 I1）：每轮恰一行，任务层不再重复。
            debugLogService.reconcilePendingOnce();
        } catch (RuntimeException e) {
            log.warn("debug log reconciliation failed", e);
        } finally {
            redisManager.releaseLock(LOCK_KEY, owner);
        }
    }
}
