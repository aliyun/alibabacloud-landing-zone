package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DispatchStepTimingsTest {
    @Test
    void measuresOnlyThisDispatchAndKeepsUnstartedStepsUnknown() {
        var rows = DispatchStepTimings.build(dispatch("RUNNING", 90_000L), List.of(
                event(1, 9, 1, "step.started", 0),
                event(2, 9, 1, "step.failed", 600_000),
                event(3, 10, 1, "step.started", 100_000),
                event(4, 10, 1, "step.completed", 110_000),
                event(5, 10, 2, "step.started", 111_000)),
                List.of(step(1), step(2), step(3)), new Date(131_000));
        assertEquals(10_000L, rows.get(0).getDurationMs());
        assertEquals("done", rows.get(0).getStatus());
        assertEquals(20_000L, rows.get(1).getDurationMs());
        assertEquals("active", rows.get(1).getStatus());
        assertNull(rows.get(2).getDurationMs());
        assertEquals("pending", rows.get(2).getStatus());
    }

    @Test
    void freezesIncompleteIntervalsAfterTerminationOrPause() {
        for (String status : List.of("FAILED", "CANCELED", "SUCCEEDED", "PAUSED")) {
            var rows = DispatchStepTimings.build(dispatch(status, 30_000L),
                    List.of(event(1, 10, 1, "step.started", 10_000)),
                    List.of(step(1)), new Date(900_000));
            assertEquals(20_000L, rows.get(0).getDurationMs(), status);
            assertNotEquals("active", rows.get(0).getStatus(), status);
        }
    }

    @Test
    void accumulatesRerunsWithoutCountingTheGapOrDuplicateStarts() {
        var rows = DispatchStepTimings.build(dispatch("SUCCEEDED", 100_000L), List.of(
                event(1, 10, 1, "step.started", 0),
                event(2, 10, 1, "step.started", 1_000),
                event(3, 10, 1, "step.failed", 10_000),
                event(4, 10, 1, "step.started", 80_000),
                event(5, 10, 1, "step.completed", 100_000)),
                List.of(step(1)), new Date(900_000));
        assertEquals(30_000L, rows.get(0).getDurationMs());
    }

    @Test
    void preservesMissingMeasurementsAndReusedState() {
        var rows = DispatchStepTimings.build(dispatch("SUCCEEDED", 30_000L), List.of(
                event(1, 10, 1, "step.completed", 10_000),
                event(2, 10, 2, "step.reused", 11_000)),
                List.of(step(1), step(2)), new Date(900_000));
        assertNull(rows.get(0).getDurationMs());
        assertEquals("done", rows.get(0).getStatus());
        assertNull(rows.get(1).getDurationMs());
        assertEquals("reused", rows.get(1).getStatus());
    }

    @Test
    void retainsRecordedNamesAndBoundsClockSkewAndMissingEndTimes() {
        var start = event(1, 10, 1, "step.started", 10_000);
        start.setStepName("Original stage");
        var rows = DispatchStepTimings.build(dispatch("FAILED", null), List.of(start),
                List.of(step(1)), new Date(900_000));
        assertEquals(0L, rows.get(0).getDurationMs());
        assertEquals("Original stage", rows.get(0).getName());
        var reversed = DispatchStepTimings.build(dispatch("FAILED", 5_000L), List.of(start),
                List.of(step(1)), new Date(900_000));
        assertEquals(0L, reversed.get(0).getDurationMs());
    }

    private DispatchDO dispatch(String status, Long end) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(10L);
        dispatch.setStatus(status);
        if (end != null) dispatch.setGmtModified(new Date(end));
        return dispatch;
    }

    private SdlcStepDO step(int id) {
        SdlcStepDO step = new SdlcStepDO();
        step.setId((long) id);
        step.setStepOrder(id);
        step.setName("Stage " + id);
        return step;
    }

    private DispatchRuntimeEventDO event(long id, long dispatchId, int step, String type, long time) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setId(id);
        event.setDispatchId(dispatchId);
        event.setStepId((long) step);
        event.setEventType(type);
        event.setEventTime(new Date(time));
        return event;
    }
}
