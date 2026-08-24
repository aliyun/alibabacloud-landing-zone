package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeStepTimelineTest {

    private static SdlcStepDO step(long id, int order, String name) {
        SdlcStepDO s = new SdlcStepDO();
        s.setId(id);
        s.setStepOrder(order);
        s.setName(name);
        s.setCode("step" + order);
        return s;
    }

    private static DispatchRuntimeEventDO event(long id, int order, String type, long epochMillis) {
        DispatchRuntimeEventDO e = new DispatchRuntimeEventDO();
        e.setId(id);
        e.setStepOrder(order);
        e.setEventType(type);
        e.setEventTime(new Date(epochMillis));
        e.setGmtCreate(new Date(epochMillis));
        return e;
    }

    /** eventTime 为空、只有 gmtCreate 的事件。 */
    private static DispatchRuntimeEventDO eventWithoutEventTime(long id, int order, String type, long epochMillis) {
        DispatchRuntimeEventDO e = new DispatchRuntimeEventDO();
        e.setId(id);
        e.setStepOrder(order);
        e.setEventType(type);
        e.setEventTime(null);
        e.setGmtCreate(new Date(epochMillis));
        return e;
    }

    @Test
    void doesNotMutateTheOrderOfSharedEventBuckets() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        // id 递增但 eventTime 乱序：若就地重排，lastEventOf 会从 completed 变成 started
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 9_000L),
                event(2L, 1, "step.completed", 1_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(20_000L));

        assertEquals("step.completed", timeline.lastEventOf(steps.get(0)).getEventType());
    }

    @Test
    void fallsBackToGmtCreateWhenEventTimeIsNull() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        List<DispatchRuntimeEventDO> events = List.of(
                eventWithoutEventTime(1L, 1, "step.started", 1_000L),
                eventWithoutEventTime(2L, 1, "step.completed", 6_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(10_000L));

        assertEquals(5_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void measuresASingleClosedInterval() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 1_000L),
                event(2L, 1, "step.completed", 61_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(60_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void sumsIntervalsAndExcludesTheGapBetweenThem() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        // 第一次 10s 失败，中间隔 100s（CR 在跑），第二次 20s 成功 → 应为 30s，不是 130s
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 0L),
                event(2L, 1, "step.failed", 10_000L),
                event(3L, 1, "step.started", 110_000L),
                event(4L, 1, "step.completed", 130_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(30_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void usesNowForAnUnclosedInterval() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        List<DispatchRuntimeEventDO> events = List.of(event(1L, 1, "step.started", 1_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(46_000L));

        assertEquals(45_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void addsAnInFlightIntervalOnTopOfClosedOnes() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 0L),
                event(2L, 1, "step.failed", 10_000L),
                event(3L, 1, "step.started", 100_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(105_000L));

        assertEquals(15_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void treatsReusedAndStaleAsTerminalEvents() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "a"), step(2L, 2, "b"));
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 0L),
                event(2L, 1, "step.reused", 3_000L),
                event(3L, 2, "step.started", 0L),
                event(4L, 2, "step.stale", 7_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(3_000L, timeline.durationOf(steps.get(0)));
        assertEquals(7_000L, timeline.durationOf(steps.get(1)));
    }

    @Test
    void countsGateAndFixRequiredTimeInsideTheStep() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        // gate 与 fix_required 不闭合区间，其耗时算在该步骤内
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 0L),
                event(2L, 1, "step.gate_started", 20_000L),
                event(3L, 1, "step.fix_required", 30_000L),
                event(4L, 1, "step.gate_finished", 40_000L),
                event(5L, 1, "step.completed", 50_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(50_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void returnsNullWhenTheStepNeverStarted() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"), step(2L, 2, "自测"));
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 0L),
                event(2L, 1, "step.completed", 5_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertNull(timeline.durationOf(steps.get(1)));
    }

    @Test
    void returnsZeroRatherThanNullForAnInstantStep() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 5_000L),
                event(2L, 1, "step.completed", 5_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(0L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void pairsIntervalsCorrectlyWhenEventsArriveOutOfOrder() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        // 入参顺序与时间顺序不一致，耗时仍应为 8s
        List<DispatchRuntimeEventDO> events = List.of(
                event(2L, 1, "step.completed", 9_000L),
                event(1L, 1, "step.started", 1_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(8_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void keepsTheEarliestStartWhenStartedRepeatsWithoutATerminal() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 0L),
                event(2L, 1, "step.started", 30_000L),
                event(3L, 1, "step.completed", 50_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        assertEquals(50_000L, timeline.durationOf(steps.get(0)));
    }

    @Test
    void clampsNegativeIntervalsToZeroOnClockSkew() {
        List<SdlcStepDO> steps = List.of(step(1L, 1, "编码"));
        // completed 的 id 更大但时间早于 started（客户端时钟回拨）
        List<DispatchRuntimeEventDO> events = List.of(
                event(1L, 1, "step.started", 10_000L),
                event(2L, 1, "step.completed", 4_000L));

        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(events, steps, new Date(999_000L));

        Long duration = timeline.durationOf(steps.get(0));
        assertEquals(0L, duration);
    }
}
