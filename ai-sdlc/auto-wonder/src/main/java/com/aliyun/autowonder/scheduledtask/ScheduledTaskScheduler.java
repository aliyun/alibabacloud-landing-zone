package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;

@Component
public class ScheduledTaskScheduler {
    private static final String LOCK_KEY = "scheduled-task:scanner:lock";
    private final ScheduledTaskDao taskDao; private final ScheduledTaskTriggerService triggerService;
    private final ScheduledTaskSchedule schedule; private final RedisManager redis; private final ScheduledTaskProperties properties; private final Clock clock;
    private final ScheduledTaskCapabilityGuard capabilityGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public ScheduledTaskScheduler(ScheduledTaskDao taskDao, ScheduledTaskTriggerService triggerService,
            ScheduledTaskSchedule schedule, RedisManager redis, ScheduledTaskProperties properties,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this(taskDao, triggerService, schedule, redis, properties, Clock.systemUTC(), capabilityGuard);
    }
    ScheduledTaskScheduler(ScheduledTaskDao taskDao, ScheduledTaskTriggerService triggerService,
            ScheduledTaskSchedule schedule, RedisManager redis, ScheduledTaskProperties properties, Clock clock,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this.taskDao=taskDao; this.triggerService=triggerService; this.schedule=schedule; this.redis=redis;
        this.properties=properties; this.clock=clock; this.capabilityGuard=capabilityGuard;
    }
    @Scheduled(fixedDelayString = "${autowonder.scheduled-task.scan-fixed-delay-ms:10000}")
    @Transactional(rollbackFor = Exception.class)
    public void scan() { if (!capabilityGuard.isScannerEnabled()) return; String owner = UUID.randomUUID().toString(); if (!redis.tryAcquireLock(LOCK_KEY, owner, properties.getLockTtlSeconds() * 1000L)) return; try { scanDue(); } finally { redis.releaseLock(LOCK_KEY, owner); } }
    void scanDue() { Instant now=clock.instant(); List<ScheduledTaskDO> due=taskDao.findDue(Date.from(now), Math.max(1, properties.getScanBatchSize())); if(due==null)return; for(ScheduledTaskDO task:due) claimAndFire(task, now); }
    void claimAndFire(ScheduledTaskDO task, Instant now) {
        if (task.getNextFireAt() == null || task.getVersion() == null) return;
        Instant first = task.getNextFireAt().toInstant();
        if (metrics != null) metrics.dueLag(java.time.Duration.between(first, now).getSeconds());
        List<Instant> occurrences = dueOccurrences(task, first, now);
        if (occurrences.isEmpty()) return;
        Instant last = occurrences.get(occurrences.size() - 1);
        Instant next = "ONCE".equals(task.getScheduleType()) ? null
                : schedule.next(task.getCronExpression(), task.getTimezone(), last);
        String status = next == null ? ScheduledTaskStatus.EXHAUSTED.name() : ScheduledTaskStatus.ACTIVE.name();
        if (taskDao.claimNextFire(task.getWorkspaceId(), task.getId(), task.getVersion(), task.getNextFireAt(),
                next == null ? null : Date.from(next), Date.from(last), status, task.getCreatorId()) != 1) return;
        List<Instant> nonExpired = new ArrayList<>();
        long deadline = Math.max(0, task.getStartDeadlineSeconds() == null ? 0 : task.getStartDeadlineSeconds());
        for (Instant occurrence : occurrences) {
            if (!now.isAfter(occurrence.plusSeconds(deadline))) nonExpired.add(occurrence);
        }
        String policy = task.getMisfirePolicy() == null ? "FIRE_LATEST" : task.getMisfirePolicy();
        if (occurrences.size() == 1 && nonExpired.size() == 1) { triggerService.fireScheduled(task, first, now); return; }
        if ("SKIP_ALL".equals(policy)) {
            for (Instant occurrence : occurrences) {
                triggerService.fireMisfire(task, occurrence, now,
                        nonExpired.contains(occurrence) ? "MISFIRE_POLICY" : "START_DEADLINE");
            }
        } else if ("FIRE_ALL".equals(policy)) {
            for (Instant occurrence : occurrences) {
                if (nonExpired.contains(occurrence)) triggerService.fireMisfire(task, occurrence, now, null, true);
                else triggerService.fireMisfire(task, occurrence, now, "START_DEADLINE");
            }
        } else {
            for (Instant occurrence : occurrences) {
                boolean selected = !nonExpired.isEmpty() && occurrence.equals(nonExpired.get(nonExpired.size() - 1));
                String skipReason = selected ? null : nonExpired.contains(occurrence)
                        ? "MISFIRE_POLICY" : "START_DEADLINE";
                triggerService.fireMisfire(task, occurrence, now, skipReason);
            }
        }
    }
    private List<Instant> dueOccurrences(ScheduledTaskDO task, Instant first, Instant now) {
        Instant earliest = task.getGmtCreate() != null ? task.getGmtCreate().toInstant() : Instant.EPOCH;
        List<Instant> result = new ArrayList<>();
        if (!first.isBefore(earliest)) result.add(first);
        if ("ONCE".equals(task.getScheduleType())) return result;
        Instant cursor = first;
        int cap = Math.max(1, properties.getScanBatchSize());
        while (result.size() < cap) {
            Instant next = schedule.next(task.getCronExpression(), task.getTimezone(), cursor);
            if (next.isAfter(now)) break;
            if (!next.isBefore(earliest)) result.add(next);
            cursor = next;
        }
        return result;
    }
}
