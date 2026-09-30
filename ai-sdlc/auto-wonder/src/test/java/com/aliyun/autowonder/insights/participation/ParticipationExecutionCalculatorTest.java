package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParticipationExecutionCalculatorTest {
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private HumanAgentParticipationFact fact() {
        return new HumanAgentParticipationFact(1, "sample", START.plusSeconds(100), 100, 10, 90);
    }
    private DispatchRuntimeEventDO event(long dispatch, String type, String session, int seconds) {
        DispatchRuntimeEventDO e = new DispatchRuntimeEventDO();
        e.setDispatchId(dispatch);
        e.setWorkitemId(1L);
        e.setEventType(type);
        e.setEventTime(Date.from(START.plusSeconds(seconds)));
        e.setDetailJson("{\"sessionId\":\"" + session + "\"}");
        return e;
    }
    @Test
    void excludesInterruptionsAndUnionsConcurrentSessionsAcrossDispatches() {
        var f = fact();
        ParticipationExecutionCalculator.enrich(f, List.of(
                event(1, "dispatch.observed", "a", 0),
                event(1, "session.started", "a", 10), event(1, "session.interrupted", "a", 30),
                event(1, "session.resumed", "a", 50), event(1, "session.completed", "a", 80),
                event(2, "dispatch.observed", "b", 5),
                event(2, "session.started", "b", 20), event(2, "session.completed", "b", 40)));
        assertEquals("COMPLETE", f.executionStatus());
        assertEquals(60L, f.executionSeconds()); // [10,40] union [50,80], not 70 or 100.
    }
    @Test
    void clipsToFirstCompletionAndIgnoresLaterDispatches() {
        var f = fact();
        ParticipationExecutionCalculator.enrich(f, List.of(
                event(1, "dispatch.observed", "a", -20),
                event(1, "session.started", "a", -10), event(1, "session.completed", "a", 120),
                event(1, "session.resumed", "a", 130),
                event(2, "dispatch.observed", "b", 110), event(2, "session.started", "b", 120)));
        assertEquals(100L, f.executionSeconds());
    }
    @Test
    void missingOrOpenBoundariesCannotProduceZeroOrPartialAverage() {
        var f = fact();
        ParticipationExecutionCalculator.enrich(f, List.of(event(1, "dispatch.observed", "a", 0)));
        assertNull(f.executionSeconds());
        assertEquals("MISSING", f.executionStatus());
        ParticipationExecutionCalculator.enrich(f, List.of(event(1, "dispatch.observed", "a", 0),
                event(1, "session.started", "a", 10), event(1, "session.completed", "a", 20),
                event(2, "dispatch.observed", "b", 0), event(2, "session.started", "b", 30)));
        assertNull(f.executionSeconds());
        assertEquals("INCOMPLETE", f.executionStatus());
    }
    @Test
    void rejectsMissingStartAndClockReversal() {
        for (List<DispatchRuntimeEventDO> events : List.of(
                List.of(event(1, "dispatch.observed", "a", 0), event(1, "session.completed", "a", 30)),
                List.of(event(1, "dispatch.observed", "a", 0), event(1, "session.started", "a", 30), event(1, "session.failed", "a", 20)))) {
            var f = fact();
            ParticipationExecutionCalculator.enrich(f, events);
            assertNull(f.executionSeconds());
            assertEquals("INCOMPLETE", f.executionStatus());
        }
    }
    @Test
    void distinguishesMeasuredZeroFromMissingHistory() {
        var f = fact();
        ParticipationExecutionCalculator.enrich(f, List.of(event(1, "dispatch.observed", "a", 0),
                event(1, "session.started", "a", 10), event(1, "session.completed", "a", 10)));
        assertEquals(0L, f.executionSeconds());
        assertEquals("COMPLETE", f.executionStatus());
    }
}
