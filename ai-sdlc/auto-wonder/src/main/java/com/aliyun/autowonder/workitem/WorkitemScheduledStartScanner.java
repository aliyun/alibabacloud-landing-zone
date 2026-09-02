package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.WorkitemAssignedEvent;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Fires deferred agent deliveries once their planned start time arrives. Assignment writes
 * the schedule and skips WorkitemAssignedEvent; this scanner CAS-clears the schedule and
 * publishes the event the assignment skipped, so exactly one dispatch is ever triggered.
 */
@Component
public class WorkitemScheduledStartScanner {
    private static final Logger log = LoggerFactory.getLogger(WorkitemScheduledStartScanner.class);
    private static final String LOCK_KEY = "workitem:scheduled-start:scanner:lock";
    private static final long SYSTEM_USER_ID = 0L;

    private final WorkitemDao workitemDao;
    private final RedisManager redis;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkitemScheduledStartConfig config;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private ThreadPoolTaskScheduler scheduler;

    @Autowired
    public WorkitemScheduledStartScanner(WorkitemDao workitemDao, RedisManager redis,
            ApplicationEventPublisher eventPublisher, WorkitemScheduledStartConfig config,
            PlatformTransactionManager transactionManager) {
        this(workitemDao, redis, eventPublisher, config, new TransactionTemplate(transactionManager),
                Clock.systemDefaultZone());
    }

    WorkitemScheduledStartScanner(WorkitemDao workitemDao, RedisManager redis,
            ApplicationEventPublisher eventPublisher, WorkitemScheduledStartConfig config,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.workitemDao = workitemDao;
        this.redis = redis;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        if (!config.isScannerEnabled()) {
            return;
        }
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("workitem-scheduled-start-");
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        this.scheduler = taskScheduler;
        taskScheduler.scheduleWithFixedDelay(this::scan, Math.max(1000L, config.getScanFixedDelayMs()));
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    void scan() {
        String owner = UUID.randomUUID().toString();
        if (!redis.tryAcquireLock(LOCK_KEY, owner, config.getLockTtlSeconds() * 1000L)) {
            return;
        }
        try {
            scanDue();
        } catch (RuntimeException e) {
            log.warn("Workitem scheduled-start scan failed", e);
        } finally {
            redis.releaseLock(LOCK_KEY, owner);
        }
    }

    void scanDue() {
        Date now = Date.from(clock.instant());
        List<WorkitemDO> due = workitemDao.findScheduledDue(now, Math.max(1, config.getScanBatchSize()));
        if (due == null) {
            return;
        }
        for (WorkitemDO workitem : due) {
            dispatchDue(workitem);
        }
    }

    void dispatchDue(WorkitemDO workitem) {
        if (workitem.getScheduledStartAt() == null || workitem.getVersion() == null
                || !"AGENT".equals(workitem.getAssigneeType()) || workitem.getAssigneeRef() == null) {
            return;
        }
        // The CAS clear and the publish must share a transaction: DispatchAssignmentListener is
        // @TransactionalEventListener(AFTER_COMMIT) and silently drops events published outside
        // a transaction, which would lose the delivery after the schedule is already cleared.
        transactionTemplate.executeWithoutResult(status -> {
            int rows = workitemDao.fireScheduledStartAt(workitem.getId(), workitem.getTenantId(),
                    workitem.getVersion());
            if (rows != 1) {
                // Schedule was concurrently edited/cleared or the row moved; skip this pass.
                return;
            }
            eventPublisher.publishEvent(new WorkitemAssignedEvent(
                    workitem.getTenantId(), workitem.getId(), workitem.getCurrentStepId(),
                    workitem.getAssigneeRef(), workitem.getVersion() + 1, SYSTEM_USER_ID));
        });
    }
}
