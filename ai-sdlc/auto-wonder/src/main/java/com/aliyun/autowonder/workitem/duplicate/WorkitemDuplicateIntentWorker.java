package com.aliyun.autowonder.workitem.duplicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 重复工单检测的后台资源：固定 2 个线程，只注册推进与过期/清理两个周期任务。
 * 不为每张工单创建线程、Future 或内存定时器；所有异常都在服务内消化。
 */
@Component
public class WorkitemDuplicateIntentWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkitemDuplicateIntentWorker.class);
    static final String THREAD_NAME_PREFIX = "workitem-duplicate-intent-";
    private static final int THREAD_COUNT = 2;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final WorkitemDuplicateService duplicateService;
    private final long scanMs;
    private final long expireMs;
    private final long cleanupMs;
    private final AtomicLong lastCleanupAt = new AtomicLong();

    private ScheduledThreadPoolExecutor executor;

    public WorkitemDuplicateIntentWorker(WorkitemDuplicateService duplicateService,
            @Value("${autowonder.workitem.duplicate-intent.scan-ms:5000}") long scanMs,
            @Value("${autowonder.workitem.duplicate-intent.expire-ms:5000}") long expireMs,
            @Value("${autowonder.workitem.duplicate-intent.cleanup-ms:3600000}") long cleanupMs) {
        this.duplicateService = duplicateService;
        this.scanMs = scanMs;
        this.expireMs = expireMs;
        this.cleanupMs = cleanupMs;
    }

    @PostConstruct
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        lastCleanupAt.set(System.currentTimeMillis());
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(THREAD_COUNT, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        pool.scheduleWithFixedDelay(this::scan, 0L, Math.max(scanMs, 1L), TimeUnit.MILLISECONDS);
        pool.scheduleWithFixedDelay(this::expire, 0L, Math.max(expireMs, 1L), TimeUnit.MILLISECONDS);
        executor = pool;
    }

    @PreDestroy
    public synchronized void stop() {
        ScheduledThreadPoolExecutor pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    /** 推进任务：领取并推进任务、修复取消待办；不等待模型。 */
    public void scan() {
        try {
            duplicateService.runOnce();
        } catch (RuntimeException failure) {
            log.error("duplicate intent scan failed", failure);
        }
    }

    /** 过期任务：只做数据库终态收敛；清理按 cleanup-ms 节流，仍在同一固定线程上执行。 */
    public void expire() {
        try {
            duplicateService.expireOnce();
        } catch (RuntimeException failure) {
            log.error("duplicate intent expire scan failed", failure);
        }
        long now = System.currentTimeMillis();
        long last = lastCleanupAt.get();
        if (now - last >= Math.max(cleanupMs, 0L) && lastCleanupAt.compareAndSet(last, now)) {
            cleanup();
        }
    }

    public void cleanup() {
        try {
            duplicateService.cleanupExpiredDrafts();
        } catch (RuntimeException failure) {
            log.error("duplicate intent cleanup failed", failure);
        }
    }
}
