package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduledTaskScheduleTest {

    private final ScheduledTaskSchedule schedule = new ScheduledTaskSchedule();

    @Test
    void computesNextCronInstantInShanghai() {
        Instant next = schedule.next("0 0 2 * * *", "Asia/Shanghai",
                Instant.parse("2026-08-10T17:59:59Z"));

        assertEquals(Instant.parse("2026-08-10T18:00:00Z"), next);
    }

    @Test
    void previewsFiveStrictlyIncreasingInstants() {
        List<Instant> preview = schedule.preview("0 */10 * * * *", "Asia/Shanghai",
                Instant.parse("2026-08-10T00:00:00Z"), 5);

        assertEquals(List.of(
                Instant.parse("2026-08-10T00:10:00Z"),
                Instant.parse("2026-08-10T00:20:00Z"),
                Instant.parse("2026-08-10T00:30:00Z"),
                Instant.parse("2026-08-10T00:40:00Z"),
                Instant.parse("2026-08-10T00:50:00Z")), preview);
    }

    @Test
    void rejectsInvalidCron() {
        BizException exception = assertThrows(BizException.class,
                () -> schedule.next("not a cron", "Asia/Shanghai", Instant.EPOCH));

        assertEquals("30003", exception.getCode());
    }

    @Test
    void rejectsNonCanonicalCronMacro() {
        assertThrows(BizException.class,
                () -> schedule.next("@daily", "Asia/Shanghai", Instant.EPOCH));
    }

    @Test
    void rejectsInvalidIanaTimezone() {
        assertThrows(BizException.class,
                () -> schedule.next("0 0 2 * * *", "Mars/Olympus_Mons", Instant.EPOCH));
    }

    @Test
    void rejectsNonPositivePreviewCount() {
        assertThrows(BizException.class,
                () -> schedule.preview("0 0 2 * * *", "Asia/Shanghai", Instant.EPOCH, 0));
    }

    @Test
    void skipsMonthsWithoutRequestedDayLikeSpringCronExpression() {
        Instant next = schedule.next("0 0 0 31 * *", "UTC",
                Instant.parse("2026-04-01T00:00:00Z"));

        assertEquals(Instant.parse("2026-05-31T00:00:00Z"), next);
    }

    @Test
    void skipsNonexistentDstLocalTimeLikeSpringCronExpression() {
        Instant next = schedule.next("0 30 2 * * *", "America/New_York",
                Instant.parse("2026-03-08T06:59:59Z"));

        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), next);
    }

    @Test
    void rejectsContinuousSessionsWithParallelOverlap() {
        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateModes("CONTINUOUS", "ALLOW"));
    }

    @Test
    void acceptsContinuousSessionsWithQueuedOverlap() {
        ScheduledTaskValidator.validateModes("CONTINUOUS", "QUEUE");
    }

    @Test
    void rejectsUnknownSessionMode() {
        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateModes("SHARED", "SKIP"));
    }

    @Test
    void rejectsCronDefinitionThatAlsoHasRunAt() {
        ScheduledTaskDO task = validDefinition();
        task.setRunAt(java.util.Date.from(Instant.parse("2026-08-11T00:00:00Z")));

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void rejectsOnceDefinitionWithoutRunAt() {
        ScheduledTaskDO task = validDefinition();
        task.setScheduleType("ONCE");
        task.setCronExpression(null);

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void rejectsNonPositiveDeadline() {
        ScheduledTaskDO task = validDefinition();
        task.setStartDeadlineSeconds(0);

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void rejectsContinuousSessionWithoutPositiveAffinityTimeout() {
        ScheduledTaskDO task = validDefinition();
        task.setSessionMode("CONTINUOUS");
        task.setOverlapPolicy("QUEUE");
        task.setAffinityTimeoutSeconds(0);

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void rejectsContinuousSessionWithNegativeAffinityTimeout() {
        ScheduledTaskDO task = validDefinition();
        task.setSessionMode("CONTINUOUS");
        task.setOverlapPolicy("QUEUE");
        task.setAffinityTimeoutSeconds(-1);

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void acceptsZeroAffinityTimeoutForIsolatedSession() {
        ScheduledTaskDO task = validDefinition();
        task.setAffinityTimeoutSeconds(0);

        ScheduledTaskValidator.validateDefinition(task, schedule);
    }

    @Test
    void rejectsUnknownMisfirePolicy() {
        ScheduledTaskDO task = validDefinition();
        task.setMisfirePolicy("REPLAY_RANDOM");

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void rejectsBlankNameAndInstruction() {
        ScheduledTaskDO task = validDefinition();
        task.setName("  ");
        task.setInstructionMd("");

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    @Test
    void rejectsNonPositiveSquadAndAgentIds() {
        ScheduledTaskDO task = validDefinition();
        task.setSquadId(0L);
        task.setInitialAgentId(-1L);

        assertThrows(BizException.class,
                () -> ScheduledTaskValidator.validateDefinition(task, schedule));
    }

    private ScheduledTaskDO validDefinition() {
        ScheduledTaskDO task = new ScheduledTaskDO();
        task.setName("worker");
        task.setInstructionMd("do work");
        task.setSquadId(30L);
        task.setInitialAgentId(40L);
        task.setScheduleType("CRON");
        task.setCronExpression("0 0 2 * * *");
        task.setTimezone("Asia/Shanghai");
        task.setSessionMode("ISOLATED");
        task.setOverlapPolicy("SKIP");
        task.setMisfirePolicy("FIRE_LATEST");
        task.setStartDeadlineSeconds(21600);
        task.setAffinityTimeoutSeconds(1800);
        return task;
    }
}
