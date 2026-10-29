package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.insights.InsightsDao;
import java.util.stream.Collectors;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class HumanAgentParticipationRefreshService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HumanAgentParticipationRefreshService.class);
    static final String LOCK_PREFIX = "autowonder:insights:human-agent:refresh-lock:";
    static final String INFLIGHT_PREFIX = "autowonder:insights:human-agent:refresh-inflight:";
    static final int DISPATCH_QUERY_BATCH_SIZE = 500;

    private final InsightsDao insightsDao;
    private final HumanAgentParticipationSnapshotStore store;
    private final HumanAgentParticipationCalculator calculator;
    private final RedisManager redisManager;
    private final HumanAgentParticipationProperties properties;
    private final ThreadPoolExecutor executor;

    public HumanAgentParticipationRefreshService(InsightsDao insightsDao,
                                                   HumanAgentParticipationSnapshotStore store,
                                                   RedisManager redisManager,
                                                   HumanAgentParticipationProperties properties) {
        this.insightsDao = insightsDao;
        this.store = store;
        this.calculator = new HumanAgentParticipationCalculator();
        this.redisManager = redisManager;
        this.properties = properties;
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(properties.getRefreshQueueCapacity());
        this.executor = new ThreadPoolExecutor(
                properties.getRefreshCorePoolSize(),
                properties.getRefreshMaxPoolSize(),
                60L, TimeUnit.SECONDS, queue,
                r -> {
                    Thread t = new Thread(r, "humanAgentInsightRefresh");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public Optional<HumanAgentParticipationSnapshotStore.ParsedSnapshot> read(long tenantId) {
        return store.read(tenantId);
    }

    public boolean requestRefresh(long tenantId) {
        String inflightKey = INFLIGHT_PREFIX + tenantId;
        if (redisManager.exists(inflightKey)) {
            return true;
        }
        String lockKey = LOCK_PREFIX + tenantId;
        String ownerToken = UUID.randomUUID().toString();
        boolean locked = redisManager.tryAcquireLock(lockKey, ownerToken, properties.getLockTtlMillis());
        if (!locked) {
            return true;
        }
        redisManager.setWithExpire(inflightKey, "1", properties.getInflightTtlSeconds());
        try {
            executor.submit(() -> {
                try {
                    refresh(tenantId, computeDataThrough(), ownerToken);
                } catch (Exception e) {
                    log.warn("Async participation refresh failed tenantId={}", tenantId, e);
                } finally {
                    redisManager.del(inflightKey);
                    redisManager.releaseLock(lockKey, ownerToken);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Participation refresh rejected tenantId={} queueFull", tenantId);
            redisManager.del(inflightKey);
            redisManager.releaseLock(lockKey, ownerToken);
            return false;
        }
    }

    public boolean waitForRefresh(long tenantId, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long pollIntervalNs = TimeUnit.MILLISECONDS.toNanos(properties.getWaitPollIntervalMs());
        String inflightKey = INFLIGHT_PREFIX + tenantId;
        while (System.nanoTime() < deadline) {
            if (read(tenantId).isPresent()) {
                return true;
            }
            if (!redisManager.exists(inflightKey)) {
                return read(tenantId).isPresent();
            }
            try {
                Thread.sleep(properties.getWaitPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return read(tenantId).isPresent();
    }

    void refresh(long tenantId, LocalDate dataThrough, String ownerToken) {
        ZoneId zone = ZoneId.of(properties.getTimezone());
        Instant cutoff = dataThrough.plusDays(1).atStartOfDay(zone).toInstant();
        Date cutoffDate = Date.from(cutoff);
        int pageSize = properties.getRefreshPageSize();
        List<HumanAgentParticipationRawEventRow> allRows = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<HumanAgentParticipationRawEventRow> page =
                    insightsDao.listParticipationLifecycleEvents(tenantId, cutoffDate, offset, pageSize);
            if (page == null || page.isEmpty()) {
                break;
            }
            allRows.addAll(page);
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        List<HumanAgentParticipationFact> facts = calculator.reconstructWithQuality(allRows, cutoff);
        List<HumanAgentParticipationFact> eligible = facts.stream()
                .filter(f -> f.exclusionReason() == null).collect(Collectors.toList());
        markDispatchParticipants(tenantId, eligible, cutoffDate);
        Instant now = Instant.now();
        store.write(tenantId, facts, dataThrough.toString(), now);
        log.info("Participation refresh completed tenantId={} dataThrough={} events={} eligible={}",
                tenantId, dataThrough, allRows.size(), eligible.size());
    }

    public List<HumanAgentParticipationFact> todayFacts(long tenantId) {
        Optional<HumanAgentParticipationSnapshotStore.ParsedSnapshot> cached = store.readTail(tenantId);
        if (cached.isPresent()) {
            return cached.get().items();
        }
        ZoneId zone = ZoneId.of(properties.getTimezone());
        LocalDate today = LocalDate.now(zone);
        Instant now = Instant.now();
        Date cutoffDate = Date.from(now);
        List<Long> workitemIds = insightsDao.listWorkitemIdsCompletedBetween(
                tenantId, Date.from(today.atStartOfDay(zone).toInstant()), cutoffDate);
        List<HumanAgentParticipationFact> facts = Collections.emptyList();
        if (workitemIds != null && !workitemIds.isEmpty()) {
            List<HumanAgentParticipationRawEventRow> rows = insightsDao
                    .listParticipationLifecycleEventsByWorkitemIds(tenantId, workitemIds, cutoffDate);
            if (rows != null && !rows.isEmpty()) {
                facts = calculator.reconstructWithQuality(rows, now);
                markDispatchParticipants(tenantId, facts, cutoffDate);
            }
        }
        store.writeTail(tenantId, facts, today.toString(), now);
        log.info("Participation realtime tail rebuilt tenantId={} workitems={} facts={}",
                tenantId, workitemIds == null ? 0 : workitemIds.size(), facts.size());
        return facts;
    }

    /**
     * A workitem counts as agent participation when a dispatch existed before its first completion.
     * Only the dispatch table is read here: runtime session events belong to the removed execution metric.
     */
    void markDispatchParticipants(long tenantId, List<HumanAgentParticipationFact> facts, Date cutoffDate) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        Map<Long, List<HumanAgentParticipationFact>> byWorkitem = new LinkedHashMap<>();
        for (HumanAgentParticipationFact fact : facts) {
            if (fact.exclusionReason() != null) {
                continue;
            }
            byWorkitem.computeIfAbsent(fact.workitemId(), key -> new ArrayList<>()).add(fact);
        }
        if (byWorkitem.isEmpty()) {
            return;
        }
        List<Long> workitemIds = new ArrayList<>(byWorkitem.keySet());
        for (int from = 0; from < workitemIds.size(); from += DISPATCH_QUERY_BATCH_SIZE) {
            List<Long> batch = workitemIds.subList(from,
                    Math.min(from + DISPATCH_QUERY_BATCH_SIZE, workitemIds.size()));
            List<HumanAgentParticipationDispatchRow> rows =
                    insightsDao.listParticipationDispatchStarts(tenantId, batch, cutoffDate);
            if (rows == null) {
                continue;
            }
            for (HumanAgentParticipationDispatchRow row : rows) {
                if (row == null || row.getFirstDispatchAt() == null) {
                    continue;
                }
                List<HumanAgentParticipationFact> matched = byWorkitem.get(row.getWorkitemId());
                if (matched == null) {
                    continue;
                }
                Instant firstDispatchAt = row.getFirstDispatchAt().toInstant();
                for (HumanAgentParticipationFact fact : matched) {
                    if (firstDispatchAt.isBefore(fact.completedAt())) {
                        fact.setAgentParticipated(true);
                    }
                }
            }
        }
    }

    public void refresh(long tenantId, LocalDate dataThrough) {
        String lockKey = LOCK_PREFIX + tenantId;
        String ownerToken = UUID.randomUUID().toString();
        boolean locked = redisManager.tryAcquireLock(lockKey, ownerToken, properties.getLockTtlMillis());
        if (!locked) {
            log.info("Participation refresh lock contention tenantId={}", tenantId);
            return;
        }
        String inflightKey = INFLIGHT_PREFIX + tenantId;
        redisManager.setWithExpire(inflightKey, "1", properties.getInflightTtlSeconds());
        try {
            refresh(tenantId, dataThrough, ownerToken);
        } finally {
            redisManager.del(inflightKey);
            redisManager.releaseLock(lockKey, ownerToken);
        }
    }

    public ParticipationRefreshResult forceRefresh(long tenantId) {
        String inflightKey = INFLIGHT_PREFIX + tenantId;
        redisManager.del(inflightKey);
        String lockKey = LOCK_PREFIX + tenantId;
        String ownerToken = UUID.randomUUID().toString();
        boolean locked = redisManager.tryAcquireLock(lockKey, ownerToken, properties.getLockTtlMillis());
        if (!locked) {
            // 上一次刷新可能异常退出遗留锁；强制释放后重试一次，避免用户再也无法重建快照。
            // releaseLock 按 owner token 比对删除，旧任务结束时不会误删这里重新获取的锁。
            redisManager.del(lockKey);
            ownerToken = UUID.randomUUID().toString();
            locked = redisManager.tryAcquireLock(lockKey, ownerToken, properties.getLockTtlMillis());
            if (!locked) {
                log.info("Participation force refresh lock contention tenantId={}", tenantId);
                return ParticipationRefreshResult.LOCK_CONTENTION;
            }
        }
        String lockToken = ownerToken;
        store.deleteTail(tenantId);
        redisManager.setWithExpire(inflightKey, "1", properties.getInflightTtlSeconds());
        try {
            executor.submit(() -> {
                try {
                    refresh(tenantId, computeDataThrough(), lockToken);
                } catch (Exception e) {
                    log.warn("Force participation refresh failed tenantId={}", tenantId, e);
                } finally {
                    redisManager.del(inflightKey);
                    redisManager.releaseLock(lockKey, lockToken);
                }
            });
            return ParticipationRefreshResult.ACCEPTED;
        } catch (RejectedExecutionException e) {
            log.warn("Force participation refresh rejected tenantId={} queueFull", tenantId);
            redisManager.del(inflightKey);
            redisManager.releaseLock(lockKey, lockToken);
            return ParticipationRefreshResult.QUEUE_FULL;
        }
    }

    LocalDate computeDataThrough() {
        ZoneId zone = ZoneId.of(properties.getTimezone());
        return LocalDate.now(zone).minusDays(1);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
