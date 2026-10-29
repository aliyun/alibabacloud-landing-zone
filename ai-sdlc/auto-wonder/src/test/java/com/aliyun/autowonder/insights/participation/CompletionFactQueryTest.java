package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompletionFactQueryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private HumanAgentParticipationRefreshService refreshService;
    private HumanAgentParticipationProperties properties;
    private CompletionFactQuery query;

    @BeforeEach
    void setUp() {
        refreshService = mock(HumanAgentParticipationRefreshService.class);
        properties = new HumanAgentParticipationProperties();
        query = new CompletionFactQuery(refreshService, properties);
    }

    @Test
    void rangeForPastWindowsReadsOnlyTheSnapshot() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        ParsedSnapshot snapshot = snapshot(yesterday, fact(1, yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 60));
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot));

        List<HumanAgentParticipationFact> facts = query.range(1L, yesterday, yesterday);

        assertEquals(1, facts.size());
        assertEquals(1L, facts.get(0).workitemId());
        verify(refreshService, never()).todayFacts(anyLong());
    }

    @Test
    void rangeMergesTheTodayTailDedupesAndSortsByCompletion() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        HumanAgentParticipationFact snapshotFirst = fact(1, yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 60);
        HumanAgentParticipationFact snapshotSecond = fact(2, yesterday.atTime(11, 0).atZone(ZONE).toInstant(), 60);
        HumanAgentParticipationFact tailDuplicate = fact(2, today.atTime(9, 0).atZone(ZONE).toInstant(), 999);
        HumanAgentParticipationFact boundary = fact(3, today.atStartOfDay(ZONE).toInstant(), 30);
        when(refreshService.todayFacts(1L)).thenReturn(List.of(tailDuplicate, boundary));

        List<HumanAgentParticipationFact> facts = query.range(
                Optional.of(snapshot(yesterday, snapshotFirst, snapshotSecond)), 1L, yesterday, today);

        assertEquals(List.of(1L, 2L, 3L), facts.stream().map(HumanAgentParticipationFact::workitemId).toList());
        assertEquals(yesterday, facts.get(1).completedAt().atZone(ZONE).toLocalDate(),
                "重叠工单保留快照事实，历史值不随实时重建漂移");
        assertEquals(today.atStartOfDay(ZONE).toInstant(), facts.get(2).completedAt(),
                "恰在今日 00:00 完成的工单属于今日尾");
    }

    @Test
    void rangeWithMissingSnapshotStillServesTheTodayTail() {
        LocalDate today = LocalDate.now(ZONE);
        when(refreshService.todayFacts(1L)).thenReturn(List.of(
                fact(1, today.atTime(1, 0).atZone(ZONE).toInstant(), 120)));

        List<HumanAgentParticipationFact> facts = query.range(Optional.empty(), 1L, today, today);

        assertEquals(1, facts.size());
        assertEquals(1L, facts.get(0).workitemId());
    }

    @Test
    void rangeFiltersExcludedAndOutOfWindowFacts() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        HumanAgentParticipationFact outOfWindow = fact(1, today.minusDays(5).atTime(10, 0).atZone(ZONE).toInstant(), 60);
        HumanAgentParticipationFact excluded = fact(2, yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 0);

        List<HumanAgentParticipationFact> facts = query.range(
                Optional.of(snapshot(yesterday, outOfWindow, excluded)), 1L, yesterday, yesterday);

        assertEquals(1, facts.size());
        assertEquals(2L, facts.get(0).workitemId(), "排除事实保留在范围内供口径说明计数");
    }

    @Test
    void rangeReturnsEmptyForInvalidWindowWithoutTouchingTheCache() {
        assertTrue(query.range(1L, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1)).isEmpty());
        assertTrue(query.range(1L, null, LocalDate.of(2026, 8, 1)).isEmpty());
        assertTrue(query.range(1L, LocalDate.of(2026, 8, 1), null).isEmpty());
        verifyNoInteractions(refreshService);
    }

    @Test
    void ensureSnapshotRequestsRefreshAndWaitsOnCacheMiss() {
        ParsedSnapshot snapshot = snapshot(LocalDate.of(2026, 9, 1), fact(1,
                LocalDate.of(2026, 9, 1).atTime(10, 0).atZone(ZONE).toInstant(), 60));
        when(refreshService.read(1L)).thenReturn(Optional.empty()).thenReturn(Optional.of(snapshot));
        when(refreshService.requestRefresh(1L)).thenReturn(true);

        Optional<ParsedSnapshot> result = query.ensureSnapshot(1L);

        assertTrue(result.isPresent());
        verify(refreshService).requestRefresh(1L);
        verify(refreshService).waitForRefresh(1L, properties.getCacheMissWaitMs());
    }

    @Test
    void ensureSnapshotReturnsEmptyWhenRefreshNeverPublishes() {
        when(refreshService.read(1L)).thenReturn(Optional.empty());
        when(refreshService.requestRefresh(1L)).thenReturn(false);
        when(refreshService.waitForRefresh(eq(1L), anyLong())).thenReturn(false);

        assertTrue(query.ensureSnapshot(1L).isEmpty());
    }

    @Test
    void averageMinutesUsesTheSharedCalculatorOverTheMergedWindow() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot(yesterday,
                fact(1, yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 120),
                fact(2, yesterday.atTime(11, 0).atZone(ZONE).toInstant(), 240))));
        when(refreshService.todayFacts(1L)).thenReturn(List.of(
                fact(3, today.atTime(1, 0).atZone(ZONE).toInstant(), 0),
                exclusion(4, today.atTime(2, 0).atZone(ZONE).toInstant())));

        // (120 + 240 + 0) / 3 = 120s -> 2 分钟；排除事实不参与样本。
        assertEquals(Integer.valueOf(2), query.averageMinutes(1L, yesterday, today));
    }

    @Test
    void includesRealtimeTailOnlyFromTodayOnwards() {
        LocalDate today = LocalDate.now(ZONE);
        assertTrue(query.includesRealtimeTail(today));
        assertFalse(query.includesRealtimeTail(today.minusDays(1)));
        assertFalse(query.includesRealtimeTail(null));
    }

    @Test
    void rangeFiltersByWorkTypeAndKeepsLegacyFactsWithoutType() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        Instant at = yesterday.atTime(10, 0).atZone(ZONE).toInstant();
        HumanAgentParticipationFact requirement = fact(1, at, 60);
        requirement.setWorkType("REQ");
        HumanAgentParticipationFact bug = fact(2, at, 60);
        bug.setWorkType("BUG");
        HumanAgentParticipationFact legacy = fact(3, at, 60);
        HumanAgentParticipationFact blankType = fact(4, at, 60);
        blankType.setWorkType("  ");

        List<HumanAgentParticipationFact> facts = query.range(
                Optional.of(snapshot(yesterday, requirement, bug, legacy, blankType)),
                1L, yesterday, yesterday, " req ");

        assertEquals(List.of(1L, 3L, 4L), facts.stream().map(HumanAgentParticipationFact::workitemId).toList(),
                "缺失或空白的 workType 视为匹配所有类型");
    }

    @Test
    void rangeFiltersTheTodayTailTooAndTreatsAllOrUnknownTypesAsUnfiltered() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        HumanAgentParticipationFact snapshotFact = fact(1, yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 60);
        snapshotFact.setWorkType("REQ");
        HumanAgentParticipationFact tailBug = fact(2, today.atTime(9, 0).atZone(ZONE).toInstant(), 60);
        tailBug.setWorkType("BUG");
        when(refreshService.todayFacts(1L)).thenReturn(List.of(tailBug));
        Optional<ParsedSnapshot> snapshot = Optional.of(snapshot(yesterday, snapshotFact));

        assertTrue(query.range(snapshot, 1L, yesterday, today, "TASK").isEmpty(),
                "实时尾同样参与类型过滤");
        for (String filter : new String[]{null, "ALL", "all", "EPIC"}) {
            assertEquals(List.of(1L, 2L),
                    query.range(snapshot, 1L, yesterday, today, filter).stream()
                            .map(HumanAgentParticipationFact::workitemId).toList(),
                    "缺省、全部与无法识别的类型都不做过滤：" + filter);
        }
    }

    @Test
    void rangeTriggersDefensiveRebuildWhenEveryFactLacksWorkType() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        Instant at = yesterday.atTime(10, 0).atZone(ZONE).toInstant();
        when(refreshService.requestRefresh(1L)).thenReturn(true);

        List<HumanAgentParticipationFact> facts = query.range(
                Optional.of(snapshot(yesterday, fact(1, at, 60), fact(2, at, 60))),
                1L, yesterday, yesterday, "REQ");

        assertEquals(List.of(1L, 2L), facts.stream().map(HumanAgentParticipationFact::workitemId).toList(),
                "旧快照仍按兼容语义返回全部事实");
        verify(refreshService).requestRefresh(1L);
    }

    @Test
    void rangeTriggersAtMostOneRebuildPerTenantPerHour() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        LocalDate earlier = today.minusDays(2);
        Instant at = yesterday.atTime(10, 0).atZone(ZONE).toInstant();
        Optional<ParsedSnapshot> snapshot = Optional.of(snapshot(yesterday,
                fact(1, at, 60), fact(2, earlier.atTime(10, 0).atZone(ZONE).toInstant(), 60)));

        query.range(snapshot, 1L, earlier, yesterday, "BUG");
        query.range(snapshot, 1L, earlier, yesterday, "TASK");

        verify(refreshService, times(1)).requestRefresh(1L);
    }

    @Test
    void rangeSkipsRebuildWhenFactsCarryWorkType() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        HumanAgentParticipationFact requirement = fact(1, yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 60);
        requirement.setWorkType("REQ");
        HumanAgentParticipationFact bug = fact(2, yesterday.atTime(11, 0).atZone(ZONE).toInstant(), 60);
        bug.setWorkType("BUG");

        List<HumanAgentParticipationFact> facts = query.range(
                Optional.of(snapshot(yesterday, requirement, bug)), 1L, yesterday, yesterday, "REQ");

        assertEquals(List.of(1L), facts.stream().map(HumanAgentParticipationFact::workitemId).toList());
        verify(refreshService, never()).requestRefresh(anyLong());
    }

    @Test
    void rangeSkipsRebuildWhenTheWindowHasNoFacts() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        HumanAgentParticipationFact outsideWindow = fact(1,
                today.minusDays(5).atTime(10, 0).atZone(ZONE).toInstant(), 60);

        List<HumanAgentParticipationFact> facts = query.range(
                Optional.of(snapshot(yesterday, outsideWindow)), 1L, yesterday, yesterday, "TASK");

        assertTrue(facts.isEmpty());
        verify(refreshService, never()).requestRefresh(anyLong());
    }

    private static ParsedSnapshot snapshot(LocalDate dataThrough, HumanAgentParticipationFact... facts) {
        return new ParsedSnapshot("2026-10-22T03:00:00Z", dataThrough.toString(), List.of(facts));
    }

    private static HumanAgentParticipationFact fact(long id, Instant completedAt, long seconds) {
        return new HumanAgentParticipationFact(id, "w" + id, completedAt, seconds, seconds / 2, seconds / 2);
    }

    private static HumanAgentParticipationFact exclusion(long id, Instant completedAt) {
        HumanAgentParticipationFact fact = new HumanAgentParticipationFact(id, "w" + id, completedAt, 0, 0, 0);
        fact.setExclusionReason("MISSING_CREATE");
        return fact;
    }
}
