package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.mockito.Mockito.*;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.codahale.metrics.MetricRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskSchedulerTest {
    @Test
    void activeRunGaugeReturnsZeroWithoutQueryingScheduledSchema() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        when(guard.isAvailable()).thenReturn(false);
        ScheduledTaskMetrics metrics = new ScheduledTaskMetrics(new MetricRegistry(), runDao, guard);

        assertEquals(0L, metrics.activeRuns());

        verify(guard).isAvailable();
        verifyNoInteractions(runDao);
    }

    @Test
    void unavailableScannerTouchesNeitherRedisNorScheduledDao() {
        ScheduledTaskDao tasks = mock(ScheduledTaskDao.class);
        ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);
        RedisManager redis = mock(RedisManager.class);
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);

        new ScheduledTaskScheduler(tasks, trigger, new ScheduledTaskSchedule(), redis,
                new ScheduledTaskProperties(), Clock.systemUTC(), guard).scan();

        verify(guard).isScannerEnabled();
        verifyNoInteractions(tasks, trigger, redis);
    }

    @Test
    void disabledScannerReturnsBeforeTakingRedisLock() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(false);
        RedisManager redis = mock(RedisManager.class);
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(mock(ScheduledTaskDao.class),
                mock(ScheduledTaskTriggerService.class), new ScheduledTaskSchedule(), redis, properties,
                Clock.systemUTC(), mock(ScheduledTaskCapabilityGuard.class));
        scheduler.scan();
        verifyNoInteractions(redis);
    }

    @Test
    void acquiredLockIsReleasedWithItsOwnerToken() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties(); properties.setEnabled(true);
        RedisManager redis = mock(RedisManager.class); when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ScheduledTaskDao tasks = mock(ScheduledTaskDao.class); when(tasks.findDue(any(), anyInt())).thenReturn(List.of());
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(tasks, mock(ScheduledTaskTriggerService.class),
                new ScheduledTaskSchedule(), redis, properties, Clock.systemUTC(), scannerEnabledGuard());
        scheduler.scan();
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(redis).tryAcquireLock(eq("scheduled-task:scanner:lock"), token.capture(), anyLong());
        verify(redis).releaseLock("scheduled-task:scanner:lock", token.getValue());
        verify(redis, never()).del(anyString());
    }

    @Test
    void failedCasNeverTriggersRun() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties(); properties.setEnabled(true);
        RedisManager redis = mock(RedisManager.class); when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ScheduledTaskDao tasks = mock(ScheduledTaskDao.class); ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);
        ScheduledTaskDO task = onceDue(); when(tasks.findDue(any(), anyInt())).thenReturn(List.of(task)); when(tasks.claimNextFire(anyLong(), anyLong(), anyInt(), any(), any(), any(), anyString(), anyLong())).thenReturn(0);
        new ScheduledTaskScheduler(tasks, trigger, new ScheduledTaskSchedule(), redis, properties,
                Clock.systemUTC(), scannerEnabledGuard()).scan();
        verifyNoInteractions(trigger);
    }

    @Test
    void onceClaimExhaustsCursorBeforeFiring() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties(); properties.setEnabled(true);
        RedisManager redis = mock(RedisManager.class); when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        ScheduledTaskDao tasks = mock(ScheduledTaskDao.class); ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);
        ScheduledTaskDO task = onceDue(); when(tasks.findDue(any(), anyInt())).thenReturn(List.of(task)); when(tasks.claimNextFire(anyLong(), anyLong(), anyInt(), any(), any(), any(), anyString(), anyLong())).thenReturn(1);
        new ScheduledTaskScheduler(tasks, trigger, new ScheduledTaskSchedule(), redis, properties,
                Clock.systemUTC(), scannerEnabledGuard()).scan();
        verify(tasks).claimNextFire(eq(1L), eq(9L), eq(0), any(), isNull(), any(), eq("EXHAUSTED"), eq(3L));
        verify(trigger).fireScheduled(eq(task), any(), any());
    }

    @Test
    void fireLatestSkipsExpiredOccurrencesAndFiresOnlyNewestOnes() {
        Instant now = Instant.parse("2026-08-10T18:00:00Z");
        Instant first = now.minusSeconds(120);
        Instant second = now.minusSeconds(30);
        ScheduledTaskDO task = cronDue(first, "FIRE_LATEST");
        ScheduledTaskDao tasks = claimedDue(task);
        ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);
        ScheduledTaskSchedule schedule = cronSchedule(first, second, now.plusSeconds(30));

        scheduler(tasks, trigger, schedule, now).scan();

        verify(trigger).fireMisfire(task, first, now, "START_DEADLINE");
        verify(trigger).fireMisfire(task, second, now, null);
    }

    @Test
    void fireAllKeepsEveryNonExpiredOccurrenceAndRecordsExpiredOnes() {
        Instant now = Instant.parse("2026-08-10T18:00:00Z");
        Instant first = now.minusSeconds(120);
        Instant second = now.minusSeconds(30);
        ScheduledTaskDO task = cronDue(first, "FIRE_ALL");
        ScheduledTaskDao tasks = claimedDue(task);
        ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);

        scheduler(tasks, trigger, cronSchedule(first, second, now.plusSeconds(30)), now).scan();

        verify(trigger).fireMisfire(task, first, now, "START_DEADLINE");
        verify(trigger).fireMisfire(task, second, now, null, true);
    }

    @Test
    void skipAllRecordsEveryDueOccurrenceAsSkipped() {
        Instant now = Instant.parse("2026-08-10T18:00:00Z");
        Instant first = now.minusSeconds(120);
        Instant second = now.minusSeconds(30);
        ScheduledTaskDO task = cronDue(first, "SKIP_ALL");
        ScheduledTaskDao tasks = claimedDue(task);
        ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);

        scheduler(tasks, trigger, cronSchedule(first, second, now.plusSeconds(30)), now).scan();

        verify(trigger).fireMisfire(task, first, now, "START_DEADLINE");
        verify(trigger).fireMisfire(task, second, now, "MISFIRE_POLICY");
    }

    @Test
    void cronClaimAdvancesUtcCursorAfterTheCappedBatch() {
        Instant now = Instant.parse("2026-08-10T18:00:00Z");
        Instant first = now.minusSeconds(90);
        Instant second = now.minusSeconds(30);
        Instant future = now.plusSeconds(30);
        ScheduledTaskDO task = cronDue(first, "FIRE_ALL");
        ScheduledTaskDao tasks = claimedDue(task);
        ScheduledTaskTriggerService trigger = mock(ScheduledTaskTriggerService.class);
        ScheduledTaskSchedule schedule = cronSchedule(first, second, future);

        scheduler(tasks, trigger, schedule, now).scan();

        verify(tasks).claimNextFire(eq(1L), eq(9L), eq(0), eq(Date.from(first)), eq(Date.from(future)),
                eq(Date.from(second)), eq("ACTIVE"), eq(3L));
    }

    private static ScheduledTaskScheduler scheduler(ScheduledTaskDao tasks, ScheduledTaskTriggerService trigger,
            ScheduledTaskSchedule schedule, Instant now) {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(true); properties.setScanBatchSize(2);
        RedisManager redis = mock(RedisManager.class);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        return new ScheduledTaskScheduler(tasks, trigger, schedule, redis, properties,
                Clock.fixed(now, ZoneOffset.UTC), scannerEnabledGuard());
    }

    private static ScheduledTaskCapabilityGuard scannerEnabledGuard() {
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        when(guard.isScannerEnabled()).thenReturn(true);
        return guard;
    }

    private static ScheduledTaskDao claimedDue(ScheduledTaskDO task) {
        ScheduledTaskDao tasks = mock(ScheduledTaskDao.class);
        when(tasks.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(tasks.claimNextFire(anyLong(), anyLong(), anyInt(), any(), any(), any(), anyString(), anyLong())).thenReturn(1);
        return tasks;
    }

    private static ScheduledTaskSchedule cronSchedule(Instant first, Instant second, Instant future) {
        ScheduledTaskSchedule schedule = mock(ScheduledTaskSchedule.class);
        when(schedule.next("0 * * * * *", "UTC", first)).thenReturn(second);
        when(schedule.next("0 * * * * *", "UTC", second)).thenReturn(future);
        return schedule;
    }

    private static ScheduledTaskDO cronDue(Instant first, String misfirePolicy) {
        ScheduledTaskDO task = onceDue();
        task.setScheduleType("CRON"); task.setCronExpression("0 * * * * *"); task.setTimezone("UTC");
        task.setMisfirePolicy(misfirePolicy); task.setNextFireAt(Date.from(first));
        task.setStartDeadlineSeconds(60);
        return task;
    }

    private static ScheduledTaskDO onceDue() {
        ScheduledTaskDO task = new ScheduledTaskDO(); task.setId(9L); task.setWorkspaceId(1L); task.setVersion(0); task.setCreatorId(3L); task.setStatus("ACTIVE"); task.setScheduleType("ONCE"); task.setNextFireAt(new Date(System.currentTimeMillis() - 1_000)); task.setStartDeadlineSeconds(60); return task;
    }
}
