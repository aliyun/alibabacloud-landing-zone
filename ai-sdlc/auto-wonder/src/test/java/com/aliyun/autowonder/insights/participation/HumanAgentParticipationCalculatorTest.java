package com.aliyun.autowonder.insights.participation;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HumanAgentParticipationCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final HumanAgentParticipationCalculator calculator = new HumanAgentParticipationCalculator();

    private HumanAgentParticipationRawEventRow createEvent(long workitemId, String title, Instant at) {
        HumanAgentParticipationRawEventRow r = new HumanAgentParticipationRawEventRow();
        r.setWorkitemId(workitemId);
        r.setTitle(title);
        r.setWorkitemCreatedAt(Date.from(at));
        r.setEventId(nextId());
        r.setEventType("CREATE");
        r.setEventAt(Date.from(at));
        r.setTerminal(false);
        return r;
    }

    private HumanAgentParticipationRawEventRow assignEvent(long workitemId, Instant at, String fromType, String toType) {
        HumanAgentParticipationRawEventRow r = new HumanAgentParticipationRawEventRow();
        r.setWorkitemId(workitemId);
        r.setEventId(nextId());
        r.setEventType("ASSIGN");
        r.setEventAt(Date.from(at));
        r.setTerminal(false);
        if (fromType != null || toType != null) {
            StringBuilder sb = new StringBuilder("{");
            if (fromType != null) sb.append("\"fromType\":\"").append(fromType).append("\"");
            if (fromType != null && toType != null) sb.append(",");
            if (toType != null) sb.append("\"toType\":\"").append(toType).append("\"");
            sb.append("}");
            r.setDetailJson(sb.toString());
        }
        return r;
    }

    private HumanAgentParticipationRawEventRow legacyAssignEvent(long workitemId, Instant at,
                                                                 String toVal, String inferredToType) {
        HumanAgentParticipationRawEventRow r = new HumanAgentParticipationRawEventRow();
        r.setWorkitemId(workitemId);
        r.setEventId(nextId());
        r.setEventType("ASSIGN");
        r.setEventAt(Date.from(at));
        r.setTerminal(false);
        r.setToVal(toVal);
        r.setInferredToType(inferredToType);
        return r;
    }

    private HumanAgentParticipationRawEventRow terminalEvent(long workitemId, Instant at) {
        HumanAgentParticipationRawEventRow r = new HumanAgentParticipationRawEventRow();
        r.setWorkitemId(workitemId);
        r.setEventId(nextId());
        r.setEventType("STATUS_CHANGE");
        r.setToVal("released");
        r.setEventAt(Date.from(at));
        r.setTerminal(true);
        return r;
    }

    private HumanAgentParticipationRawEventRow nonTerminalStatusChange(long workitemId, Instant at) {
        HumanAgentParticipationRawEventRow r = new HumanAgentParticipationRawEventRow();
        r.setWorkitemId(workitemId);
        r.setEventId(nextId());
        r.setEventType("STATUS_CHANGE");
        r.setToVal("verifying");
        r.setEventAt(Date.from(at));
        r.setTerminal(false);
        return r;
    }

    private static long idCounter = 1000;
    private static long nextId() { return ++idCounter; }

    @Test
    void reconstructsSimpleHumanToAgentWorkitem() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(8));
        Instant t2 = t0.plus(Duration.ofHours(40));
        Instant t3 = t0.plus(Duration.ofHours(48));

        List<HumanAgentParticipationRawEventRow> rows = List.of(
                createEvent(1L, "test-1", t0),
                assignEvent(1L, t1, "HUMAN", "AGENT"),
                assignEvent(1L, t2, "AGENT", "HUMAN"),
                terminalEvent(1L, t3)
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, t3.plusSeconds(1));
        assertEquals(1, facts.size());
        HumanAgentParticipationFact f = facts.get(0);
        assertEquals(48 * 3600, f.totalDurationSeconds());
        assertEquals(8 * 3600 + 8 * 3600, f.humanDurationSeconds());
        assertEquals(32 * 3600, f.agentDurationSeconds());
    }

    @Test
    void skipsAmbiguousAssignEventAndCountsAllDurationAsHuman() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(4));
        Instant t2 = t0.plus(Duration.ofHours(8));

        HumanAgentParticipationRawEventRow ambiguousAssign = assignEvent(1L, t1, null, null);

        List<HumanAgentParticipationRawEventRow> rows = List.of(
                createEvent(1L, "test-1", t0),
                ambiguousAssign,
                terminalEvent(1L, t2)
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, t2.plusSeconds(1));
        assertEquals(1, facts.size());
        assertEquals(Duration.between(t0, t2).getSeconds(), facts.get(0).totalDurationSeconds());
        assertEquals(Duration.between(t0, t2).getSeconds(), facts.get(0).humanDurationSeconds());
        assertEquals(0, facts.get(0).agentDurationSeconds());
    }

    @Test
    void excludesWorkitemWithNoTerminalEvent() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(4));

        List<HumanAgentParticipationRawEventRow> rows = List.of(
                createEvent(1L, "test-1", t0),
                assignEvent(1L, t1, "HUMAN", "AGENT"),
                nonTerminalStatusChange(1L, t1.plus(Duration.ofHours(2)))
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, t1.plus(Duration.ofDays(1)));
        assertTrue(facts.isEmpty());
    }

    @Test
    void excludesWorkitemCompletedAfterCutoff() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant terminal = t0.plus(Duration.ofDays(3));
        Instant cutoff = t0.plus(Duration.ofDays(2));

        List<HumanAgentParticipationRawEventRow> rows = List.of(
                createEvent(1L, "test-1", t0),
                terminalEvent(1L, terminal)
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, cutoff);
        assertTrue(facts.isEmpty());
    }

    @Test
    void allHumanWorkitem() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(24));

        List<HumanAgentParticipationRawEventRow> rows = List.of(
                createEvent(1L, "test-1", t0),
                terminalEvent(1L, t1)
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, t1.plusSeconds(1));
        assertEquals(1, facts.size());
        assertEquals(24 * 3600, facts.get(0).totalDurationSeconds());
        assertEquals(24 * 3600, facts.get(0).humanDurationSeconds());
        assertEquals(0, facts.get(0).agentDurationSeconds());
    }

    @Test
    void p90NearestRank() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        List<HumanAgentParticipationFact> facts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Instant completed = base.plus(Duration.ofHours(i));
            facts.add(new HumanAgentParticipationFact(i, "w" + i, completed,
                    i * 3600L, i * 1800L, i * 1800L));
        }

        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(facts, LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31), HumanAgentParticipationCalculator.Granularity.MONTH, ZONE);

        assertEquals(10, summary.eligibleFacts().size());
        assertNotNull(summary.p90());
        assertEquals(9, summary.p90().workitemId());
    }

    @Test
    void slowTailSizeAndOrder() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        List<HumanAgentParticipationFact> facts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Instant completed = base.plus(Duration.ofHours(i));
            facts.add(new HumanAgentParticipationFact(i, "w" + i, completed,
                    i * 3600L, i * 1800L, i * 1800L));
        }

        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(facts, LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31), HumanAgentParticipationCalculator.Granularity.MONTH, ZONE);

        assertEquals(1, summary.slowTail().size());
        assertEquals(10, summary.slowTail().get(0).workitemId());
    }

    @Test
    void dayWeekMonthBucketLabels() {
        Instant t = ZonedDateTime.of(2026, 8, 4, 15, 0, 0, 0, ZONE).toInstant();
        assertEquals("2026-08-04",
                HumanAgentParticipationCalculator.bucketLabel(t, HumanAgentParticipationCalculator.Granularity.DAY, ZONE));
        assertEquals("2026-08-03",
                HumanAgentParticipationCalculator.bucketLabel(t, HumanAgentParticipationCalculator.Granularity.WEEK, ZONE));
        assertEquals("2026-08-01",
                HumanAgentParticipationCalculator.bucketLabel(t, HumanAgentParticipationCalculator.Granularity.MONTH, ZONE));
    }

    @Test
    void summarizeEmptyRangeReturnsZeroAverages() {
        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(Collections.emptyList(), LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31), HumanAgentParticipationCalculator.Granularity.DAY, ZONE);

        assertEquals(0, summary.eligibleFacts().size());
        assertEquals(0, summary.averageTotalSeconds());
        assertNull(summary.p90());
        assertTrue(summary.trend().isEmpty());
    }

    @Test
    void averageHumanReconcilesWithTotal() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        List<HumanAgentParticipationFact> facts = List.of(
                new HumanAgentParticipationFact(1, "a", base, 100, 40, 60),
                new HumanAgentParticipationFact(2, "b", base.plusSeconds(1), 200, 80, 120),
                new HumanAgentParticipationFact(3, "c", base.plusSeconds(2), 300, 120, 180)
        );

        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(facts, LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31), HumanAgentParticipationCalculator.Granularity.MONTH, ZONE);

        assertEquals(200, summary.averageTotalSeconds());
        assertEquals(80, summary.averageHumanSeconds());
        assertEquals(120, summary.averageAgentSeconds());
        assertEquals(summary.averageTotalSeconds(),
                summary.averageHumanSeconds() + summary.averageAgentSeconds());
    }

    @Test
    void ignoresAssignEventsAfterTerminalEvent() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(2));
        Instant t2 = t0.plus(Duration.ofHours(6));
        Instant t3 = t0.plus(Duration.ofHours(10));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        List<HumanAgentParticipationRawEventRow> rows = Arrays.asList(
                createEvent(1L, "test-post-terminal", t0),
                assignEvent(1L, t1, "HUMAN", "AGENT"),
                terminalEvent(1L, t2),
                assignEvent(1L, t3, "AGENT", "HUMAN")
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, cutoff);
        assertEquals(1, facts.size());
        HumanAgentParticipationFact f = facts.get(0);
        assertEquals(Duration.between(t0, t2).getSeconds(), f.totalDurationSeconds());
        assertEquals(Duration.between(t0, t1).getSeconds(), f.humanDurationSeconds());
        assertEquals(Duration.between(t1, t2).getSeconds(), f.agentDurationSeconds());
        assertEquals(f.totalDurationSeconds(), f.humanDurationSeconds() + f.agentDurationSeconds());
    }

    @Test
    void humanPlusAgentAlwaysEqualsTotal() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(1));
        Instant t2 = t0.plus(Duration.ofHours(3));
        Instant t3 = t0.plus(Duration.ofHours(5));
        Instant t4 = t0.plus(Duration.ofHours(7));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        List<HumanAgentParticipationRawEventRow> rows = Arrays.asList(
                createEvent(1L, "multi-assign", t0),
                assignEvent(1L, t1, "HUMAN", "AGENT"),
                assignEvent(1L, t2, "AGENT", "HUMAN"),
                assignEvent(1L, t3, "HUMAN", "AGENT"),
                terminalEvent(1L, t4),
                assignEvent(1L, t4.plus(Duration.ofHours(2)), "AGENT", "HUMAN")
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, cutoff);
        assertEquals(1, facts.size());
        HumanAgentParticipationFact f = facts.get(0);
        assertEquals(f.totalDurationSeconds(), f.humanDurationSeconds() + f.agentDurationSeconds());
        assertTrue(f.humanDurationSeconds() >= 0);
        assertTrue(f.agentDurationSeconds() >= 0);
    }

    @Test
    void summarizeAverageHumanNeverNegative() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        List<HumanAgentParticipationFact> facts = List.of(
                new HumanAgentParticipationFact(1, "a", base, 100, 30, 70),
                new HumanAgentParticipationFact(2, "b", base.plusSeconds(1), 200, 50, 150),
                new HumanAgentParticipationFact(3, "c", base.plusSeconds(2), 150, 60, 90)
        );

        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(facts, LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31), HumanAgentParticipationCalculator.Granularity.MONTH, ZONE);

        assertTrue(summary.averageHumanSeconds() >= 0,
                "averageHumanSeconds should never be negative, was: " + summary.averageHumanSeconds());
        assertTrue(summary.averageAgentSeconds() >= 0);
        assertTrue(summary.averageTotalSeconds() >= 0);
    }

    @Test
    void skipsAssignEventsWithoutToTypeInsteadOfRejectingWorkitem() {
        Instant t0 = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(4));
        Instant t2 = t0.plus(Duration.ofHours(24));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        List<HumanAgentParticipationRawEventRow> rows = Arrays.asList(
                createEvent(1L, "Historical Task", t0),
                assignEvent(1L, t1, null, null),
                terminalEvent(1L, t2)
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, cutoff);
        assertEquals(1, facts.size());
        HumanAgentParticipationFact fact = facts.get(0);
        assertEquals(1L, fact.workitemId());
        assertEquals(Duration.between(t0, t2).getSeconds(), fact.totalDurationSeconds());
        assertEquals(Duration.between(t0, t2).getSeconds(), fact.humanDurationSeconds());
        assertEquals(0, fact.agentDurationSeconds());
    }

    @Test
    void usesInferredAssignmentTypeWhenHistoricalAssignDetailJsonIsMissing() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofSeconds(4));
        Instant t2 = t0.plus(Duration.ofHours(2));
        Instant t3 = t0.plus(Duration.ofHours(80));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        List<HumanAgentParticipationRawEventRow> rows = Arrays.asList(
                createEvent(28518L, "historical handoff", t0),
                assignEvent(28518L, t1, "HUMAN", "AGENT"),
                legacyAssignEvent(28518L, t2, "10000", "HUMAN"),
                terminalEvent(28518L, t3)
        );

        List<HumanAgentParticipationFact> facts = calculator.reconstruct(rows, cutoff);
        assertEquals(1, facts.size());
        HumanAgentParticipationFact f = facts.get(0);
        assertEquals(Duration.between(t0, t3).getSeconds(), f.totalDurationSeconds());
        assertEquals(Duration.between(t0, t1).plus(Duration.between(t2, t3)).getSeconds(),
                f.humanDurationSeconds());
        assertEquals(Duration.between(t1, t2).getSeconds(), f.agentDurationSeconds());
    }
}
