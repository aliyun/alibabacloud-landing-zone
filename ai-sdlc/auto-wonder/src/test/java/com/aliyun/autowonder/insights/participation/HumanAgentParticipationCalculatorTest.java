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
    void excludesAmbiguousAssignmentInsteadOfInventingHumanTime() {
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
        assertTrue(facts.isEmpty());
        assertEquals("UNKNOWN_ASSIGNEE", calculator.reconstructWithQuality(rows, t2.plusSeconds(1)).get(0).exclusionReason());
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
    void completionExactlyAtCutoffBelongsToTheRealtimeTail() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant midnight = ZonedDateTime.of(2026, 8, 2, 0, 0, 0, 0, ZONE).toInstant();

        List<HumanAgentParticipationRawEventRow> rows = List.of(
                createEvent(1L, "boundary", t0),
                terminalEvent(1L, midnight)
        );

        assertTrue(calculator.reconstruct(rows, midnight).isEmpty(),
                "快照 cutoff 左闭右开，00:00 完成的工单必须留给今日实时尾");
        List<HumanAgentParticipationFact> tail = calculator.reconstruct(rows, midnight.plusSeconds(1));
        assertEquals(1, tail.size());
        assertEquals("OK", tail.get(0).chainQuality());
    }

    @Test
    void marksBrokenChainsWithChainQualityInsteadOfDroppingTheReason() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(2));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        List<HumanAgentParticipationRawEventRow> missingCreate = Arrays.asList(
                assignEvent(2L, t1.minus(Duration.ofHours(1)), "HUMAN", "AGENT"),
                terminalEvent(2L, t1));
        List<HumanAgentParticipationRawEventRow> invalidTimeline = Arrays.asList(
                createEvent(3L, "out-of-order", t0),
                assignEvent(3L, t0.minus(Duration.ofHours(1)), "HUMAN", "AGENT"),
                terminalEvent(3L, t1));

        List<HumanAgentParticipationFact> facts = calculator.reconstructWithQuality(
                new ArrayList<HumanAgentParticipationRawEventRow>() {{
                    addAll(missingCreate);
                    addAll(invalidTimeline);
                }}, cutoff);

        Map<Long, String> qualities = facts.stream()
                .collect(java.util.stream.Collectors.toMap(HumanAgentParticipationFact::workitemId,
                        HumanAgentParticipationFact::chainQuality));
        assertEquals("MISSING_CREATE", qualities.get(2L));
        assertEquals("INVALID_TIMELINE", qualities.get(3L));
    }

    @Test
    void averageMinutesRoundsTheSharedCaliberAndSkipsExcludedFacts() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        assertEquals(Integer.valueOf(3), HumanAgentParticipationCalculator.averageMinutes(List.of(
                new HumanAgentParticipationFact(1, "a", base, 120, 60, 60),
                new HumanAgentParticipationFact(2, "b", base, 240, 120, 120))));

        HumanAgentParticipationFact excluded = new HumanAgentParticipationFact(3, "c", base, 0, 0, 0);
        excluded.setExclusionReason("MISSING_CREATE");
        assertEquals(Integer.valueOf(10), HumanAgentParticipationCalculator.averageMinutes(List.of(
                new HumanAgentParticipationFact(4, "d", base, 600, 300, 300), excluded)));

        assertNull(HumanAgentParticipationCalculator.averageMinutes(Collections.emptyList()));
        assertNull(HumanAgentParticipationCalculator.averageMinutes(null));
        assertNull(HumanAgentParticipationCalculator.averageMinutes(List.of(excluded)));
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
                        LocalDate.of(2026, 7, 31), ZONE);

        assertEquals(10, summary.eligibleFacts().size());
        assertNotNull(summary.p90());
        assertEquals(9, summary.p90().workitemId());
    }

    @Test
    void slowTailIsTheTenLongestWorkitemsAndKeepsSmallRangesWhole() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        List<HumanAgentParticipationFact> facts = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Instant completed = base.plus(Duration.ofHours(i));
            facts.add(new HumanAgentParticipationFact(i, "w" + i, completed,
                    i * 3600L, i * 1800L, i * 1800L));
        }

        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(facts, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZONE);

        assertEquals(10, summary.slowTail().size());
        assertEquals(12, summary.slowTail().get(0).workitemId());
        assertEquals(3, summary.slowTail().get(9).workitemId());

        HumanAgentParticipationCalculator.ParticipationSummary small =
                calculator.summarize(facts.subList(0, 3), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZONE);
        assertEquals(3, small.slowTail().size());
        assertEquals(3, small.slowTail().get(0).workitemId());
    }

    @Test
    void slowTailHelperFiltersExcludedAndOutOfRangeFacts() {
        Instant base = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        HumanAgentParticipationFact small = new HumanAgentParticipationFact(
                1, "small", base, 60, 60, 0);
        HumanAgentParticipationFact large = new HumanAgentParticipationFact(
                2, "large", base.plus(Duration.ofHours(1)), 7200, 0, 7200);
        HumanAgentParticipationFact excluded = new HumanAgentParticipationFact(
                3, "excluded", base, 9999, 9999, 0);
        excluded.setExclusionReason("MISSING_CREATE");
        HumanAgentParticipationFact outOfRange = new HumanAgentParticipationFact(
                4, "out", ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZONE).toInstant(), 9000, 9000, 0);

        List<HumanAgentParticipationFact> tail = HumanAgentParticipationCalculator.slowTail(
                List.of(small, large, excluded, outOfRange),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZONE);

        assertEquals(2, tail.size());
        assertEquals(2, tail.get(0).workitemId());
        assertEquals(1, tail.get(1).workitemId());
    }

    @Test
    void slowTailHelperHandlesNullAndEmptyInput() {
        assertTrue(HumanAgentParticipationCalculator.slowTail(
                null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZONE).isEmpty());
        assertTrue(HumanAgentParticipationCalculator.slowTail(
                Collections.emptyList(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZONE).isEmpty());
    }

    @Test
    void trendUsesNaturalDaysAndFillsEmptyDays() {
        Instant first = ZonedDateTime.of(2026, 8, 4, 15, 0, 0, 0, ZONE).toInstant();
        Instant third = ZonedDateTime.of(2026, 8, 6, 9, 0, 0, 0, ZONE).toInstant();
        List<HumanAgentParticipationFact> facts = List.of(
                new HumanAgentParticipationFact(1, "a", first, 3600, 1800, 1800),
                new HumanAgentParticipationFact(2, "b", third, 7200, 3600, 3600));

        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(facts, LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 6), ZONE);

        assertEquals(List.of("2026-08-04", "2026-08-05", "2026-08-06"), summary.trend().stream()
                .map(HumanAgentParticipationCalculator.TrendBucket::label)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(1, summary.trend().get(0).sampleSize());
        assertEquals(0, summary.trend().get(1).sampleSize());
        assertEquals(7200, summary.trend().get(2).averageTotalSeconds());
        assertEquals("2026-08-04", HumanAgentParticipationCalculator.bucketLabel(first, ZONE));
    }

    @Test
    void summarizeEmptyRangeReturnsZeroAverages() {
        HumanAgentParticipationCalculator.ParticipationSummary summary =
                calculator.summarize(Collections.emptyList(), LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31), ZONE);

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
                        LocalDate.of(2026, 7, 31), ZONE);

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
                        LocalDate.of(2026, 7, 31), ZONE);

        assertTrue(summary.averageHumanSeconds() >= 0,
                "averageHumanSeconds should never be negative, was: " + summary.averageHumanSeconds());
        assertTrue(summary.averageAgentSeconds() >= 0);
        assertTrue(summary.averageTotalSeconds() >= 0);
    }

    @Test
    void retainsExclusionReasonForUnknownHistoricalAssignment() {
        Instant t0 = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(4));
        Instant t2 = t0.plus(Duration.ofHours(24));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        List<HumanAgentParticipationRawEventRow> rows = Arrays.asList(
                createEvent(1L, "Historical Task", t0),
                assignEvent(1L, t1, null, null),
                terminalEvent(1L, t2)
        );

        assertTrue(calculator.reconstruct(rows, cutoff).isEmpty());
        assertEquals("UNKNOWN_ASSIGNEE", calculator.reconstructWithQuality(rows, cutoff).get(0).exclusionReason());
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

    @Test
    void carriesWorkTypeFromLifecycleRowsForEligibleAndExcludedFacts() {
        Instant t0 = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE).toInstant();
        Instant t1 = t0.plus(Duration.ofHours(2));
        Instant cutoff = t0.plus(Duration.ofDays(30));

        HumanAgentParticipationRawEventRow requirementCreate = createEvent(1L, "需求", t0);
        requirementCreate.setWorkType("REQ");
        HumanAgentParticipationRawEventRow requirementDone = terminalEvent(1L, t1);
        requirementDone.setWorkType("REQ");
        HumanAgentParticipationRawEventRow bugDone = terminalEvent(2L, t1);
        bugDone.setWorkType("BUG");

        List<HumanAgentParticipationFact> facts = calculator.reconstructWithQuality(
                List.of(requirementCreate, requirementDone, bugDone), cutoff);

        assertEquals(2, facts.size());
        assertEquals("REQ", facts.get(0).workType());
        assertNull(facts.get(0).exclusionReason());
        assertEquals("BUG", facts.get(1).workType());
        assertEquals("MISSING_CREATE", facts.get(1).exclusionReason());
    }
}
