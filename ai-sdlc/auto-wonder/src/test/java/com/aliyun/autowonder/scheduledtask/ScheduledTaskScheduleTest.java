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

    @Test
    void definitionReasonsAndPreviewBoundsRenderAtTheHumanBoundaryOnly() {

        java.util.List<java.util.function.Consumer<ScheduledTaskDO>> mutations = java.util.List.of(
                t -> t.setName(" "), t -> t.setInstructionMd(" "), t -> t.setSquadId(0L),
                t -> t.setTimezone(" "), t -> { t.setScheduleType("ONCE"); t.setCronExpression(null); },
                t -> t.setRunAt(new java.util.Date(0)), t -> t.setScheduleType("UNKNOWN"),
                t -> t.setSessionMode("UNKNOWN"), t -> t.setOverlapPolicy("UNKNOWN"),
                t -> { t.setSessionMode("CONTINUOUS"); t.setOverlapPolicy("ALLOW"); },
                t -> t.setMisfirePolicy("UNKNOWN"), t -> t.setStartDeadlineSeconds(0),
                t -> t.setAffinityTimeoutSeconds(null),
                t -> { t.setSessionMode("CONTINUOUS"); t.setAffinityTimeoutSeconds(0); });
        String[] diagnostics = {"任务名称不能为空且不能超过 256 个字符", "任务指令不能为空或过长", "小队和初始 Agent ID 必须为正数",
                "timezone 不能为空", "ONCE 必须且只能设置 runAt", "CRON 必须且只能设置 cronExpression", "scheduleType 仅支持 ONCE/CRON",
                "sessionMode 不合法", "overlapPolicy 不合法", "CONTINUOUS 模式不能并行执行", "misfirePolicy 不合法",
                "startDeadlineSeconds 必须为正数", "affinityTimeoutSeconds 不能为空", "CONTINUOUS 模式的 affinityTimeoutSeconds 必须为正数"};
        String[] english = {"Task name is required and must not exceed 256 characters", "Task instruction is required and must not exceed the size limit",
                "Squad and initial Agent IDs must be positive", "timezone is required", "ONCE requires only runAt", "CRON requires only cronExpression",
                "scheduleType supports only ONCE/CRON", "sessionMode is invalid", "overlapPolicy is invalid", "CONTINUOUS mode cannot run in parallel",
                "misfirePolicy is invalid", "startDeadlineSeconds must be positive", "affinityTimeoutSeconds is required",
                "affinityTimeoutSeconds must be positive in CONTINUOUS mode"};
        org.junit.jupiter.api.Assertions.assertAll(java.util.stream.IntStream.range(0, mutations.size()).mapToObj(i ->
                (org.junit.jupiter.api.function.Executable) () -> {
                    ScheduledTaskDO task = validDefinition(); mutations.get(i).accept(task);
                    assertBilingual(assertThrows(BizException.class, () -> ScheduledTaskValidator.validateDefinition(task, schedule)), diagnostics[i], english[i]);
                }));
    }

    @Test
    void nullDefinitionAndPreviewCountRenderSpecificBoundaryReasons() {
        assertBilingual(assertThrows(BizException.class, () -> ScheduledTaskValidator.validateDefinition(null, schedule)),
                "任务定义不能为空", "Task definition is required");
    }

    @Test
    void previewCountKeepsItsSpecificBounds() {
        assertBilingual(assertThrows(BizException.class, () -> schedule.preview("0 0 2 * * *", "UTC", Instant.EPOCH, 101)),
                "预览次数必须在 1 到 100 之间", "Preview count must be between 1 and 100");
    }

    private void assertBilingual(BizException failure, String diagnostic, String english) {
        assertEquals(diagnostic, failure.getMessage());
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var chinese = handler.handleBiz(failure);
            assertEquals(200, chinese.getStatusCodeValue());
            assertEquals(diagnostic, chinese.getBody().getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var translated = handler.handleBiz(failure);
            assertEquals(200, translated.getStatusCodeValue());
            assertEquals(failure.getCode(), translated.getBody().getCode());
            assertEquals(english, translated.getBody().getMessage());
            assertEquals(diagnostic, failure.getLocalizedMessage());
            assertEquals(diagnostic, com.aliyun.autowonder.common.result.Result.fail(failure.getCode(), diagnostic).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

}
