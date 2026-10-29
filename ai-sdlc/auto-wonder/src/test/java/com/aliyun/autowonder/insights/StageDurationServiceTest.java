package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.insights.StageDurationService.Event;
import com.aliyun.autowonder.insights.dto.StageDurationVO;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageDurationServiceTest {
    private final StageDurationDao dao = mock(StageDurationDao.class);
    private final StageDurationService service = new StageDurationService(dao);
    private final LocalDate start = LocalDate.of(2026, 1, 1), end = start.plusDays(6);

    @Test
    void accumulatesRerunsAndComputesCompletedPercentilesWithoutCountingGapsOrOtherFlows() {
        var events = List.of(event(1, 1, 10, "step.started", 0), event(2, 1, 10, "step.failed", 10_000),
                event(3, 1, 10, "step.started", 90_000), event(4, 1, 10, "step.completed", 110_000),
                event(5, 2, 10, "step.started", 0), event(6, 2, 10, "step.completed", 10_000),
                event(7, 3, 10, "step.started", 0), event(8, 3, 10, "step.completed", 90_000));
        given(List.of(execution(1, 5, "SUCCEEDED", 120_000), execution(2, 5, "SUCCEEDED", 120_000),
                execution(3, 6, "SUCCEEDED", 120_000)), events);
        StageDurationVO report = service.get(9, start, end, 7L, 5L);
        var stage = report.getStages().stream().filter(s -> s.getSdlcId() == 5L).findFirst().orElseThrow();
        assertEquals(40_000, stage.getTotalDurationMs());
        assertEquals(20_000, stage.getAverageDurationMs());
        assertEquals(20_000, stage.getMedianDurationMs());
        assertEquals(30_000, stage.getP90DurationMs());
        assertEquals(2, stage.getSampleCount());
        assertEquals(3, report.getExecutionCount());
        assertEquals(1, report.getWorkitemCount());
        assertEquals(3, report.getMeasuredExecutionCount());
        assertEquals(2, report.getStages().size());
        assertEquals(130_000, report.getTotalDurationMs());
        verify(dao).listExecutions(eq(9L), eq(Date.from(Instant.parse("2025-12-31T16:00:00Z"))),
                eq(Date.from(Instant.parse("2026-01-07T16:00:00Z"))), eq(7L), eq(5L), eq(5001));
        assertEquals(1, events.get(0).getStepOrder());
    }

    @Test
    void separatesRunningPausedFailedAndMissingSamplesFromCompletedStatistics() {
        given(List.of(execution(1, 5, "RUNNING", 100_000), execution(2, 5, "PAUSED", 30_000),
                execution(3, 5, "FAILED", 40_000), execution(4, 5, "SUCCEEDED", 50_000)),
                List.of(event(1, 1, 10, "step.started", 10_000), event(2, 2, 10, "step.started", 10_000),
                        event(3, 3, 10, "step.started", 10_000), event(4, 3, 10, "step.failed", 25_000),
                        event(5, 4, 10, "step.completed", 50_000)));
        var report = service.get(9, start, end, null, null);
        var stage = report.getStages().getFirst();
        assertEquals(1, stage.getActiveCount());
        assertEquals(2, stage.getIncompleteCount());
        assertEquals(1, stage.getCompletedCount());
        assertEquals(1, stage.getMissingDurationCount());
        assertNull(stage.getAverageDurationMs());
        assertNull(stage.getMedianDurationMs());
        assertNull(stage.getP90DurationMs());
        assertEquals(3, report.getMeasuredExecutionCount());
        assertEquals(Instant.parse(report.getCalculatedAt()).toEpochMilli() - 10_000,
                stage.getSlowest().stream().filter(s -> s.getDispatchId() == 1).findFirst().orElseThrow().getDurationMs());
        assertEquals(20_000, stage.getSlowest().stream().filter(s -> s.getDispatchId() == 2).findFirst().orElseThrow().getDurationMs());
    }

    @Test
    void preservesUnknownCoverageReuseSkipAndHistoricalNamesWithoutCurrentDefinitions() {
        var unknownFlow = execution(3, 5, "SUCCEEDED", 40_000); unknownFlow.setSdlcId(null);
        var done = event(1, 1, 10, "step.completed", 10_000); done.setStepName("User supplied 名称");
        given(List.of(execution(1, 5, "SUCCEEDED", 30_000), execution(2, 5, "SUCCEEDED", 30_000), unknownFlow),
                List.of(done, event(2, 1, 11, "step.reused", 20_000), event(3, 1, 12, "step.skipped", 25_000)));
        var report = service.get(9, start, end, null, null);
        assertEquals(0, report.getMeasuredExecutionCount());
        assertEquals(1, report.getMissingDurationCount());
        assertEquals(3, report.getStages().size());
        var stage = report.getStages().stream().filter(s -> s.getStepId() == 10L).findFirst().orElseThrow();
        assertEquals("User supplied 名称", stage.getName());
        assertEquals(1, report.getStages().stream().mapToInt(StageDurationVO.Stage::getReusedCount).sum());
        assertEquals(1, report.getStages().stream().mapToInt(StageDurationVO.Stage::getSkippedCount).sum());
    }

    @Test
    void pairsLegacyOrderOnlyEventsWithUniqueRecordedIdsAndKeepsDistinctIdsApart() {
        var legacyStart = event(1, 1, 10, "step.started", 0); legacyStart.setStepId(null);
        var completed = event(2, 1, 10, "step.completed", 10_000);
        var otherStart = event(3, 2, 11, "step.started", 0);
        var otherEnd = event(4, 2, 11, "step.completed", 20_000);
        given(List.of(execution(1, 5, "SUCCEEDED", 30_000), execution(2, 5, "SUCCEEDED", 30_000)),
                List.of(legacyStart, completed, otherStart, otherEnd));
        var report = service.get(9, start, end, null, null);
        assertEquals(2, report.getStages().size());
        assertEquals(30_000, report.getTotalDurationMs());
        assertEquals(0, report.getMissingDurationCount());
    }

    @Test
    void limitsDrilldownWithoutTruncatingSummarySamples() {
        List<StageDurationService.Execution> executions = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            executions.add(execution(i, 5, "SUCCEEDED", 30_000));
            events.add(event(2L * i, i, 10, "step.started", 0));
            events.add(event(2L * i + 1, i, 10, "step.completed", i * 1000L));
        }
        given(executions, events);
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals(20, stage.getCompletedCount());
        assertEquals(10_500, stage.getMedianDurationMs());
        assertEquals(18_000, stage.getP90DurationMs());
        assertEquals(10, stage.getSlowest().size());
        assertEquals(20, stage.getSlowest().getFirst().getDispatchId());
    }

    @Test
    void coversEveryExecutionAcrossBoundedQueriesAndFallsBackToExistingStepNames() {
        List<StageDurationService.Execution> executions = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        for (int id = 1; id <= 201; id++) {
            executions.add(execution(id, 5, "SUCCEEDED", 2_000));
            for (var event : List.of(event(2L * id, id, 10, "step.started", 0),
                    event(2L * id + 1, id, 10, "step.completed", 1_000))) {
                event.setStepName(null); event.setFallbackStepName("Existing stage"); events.add(event);
            }
        }
        given(executions, events);
        var report = service.get(9, start, end, null, null);
        assertEquals(201, report.getMeasuredExecutionCount());
        assertEquals(201_000, report.getTotalDurationMs());
        var stage = report.getStages().getFirst();
        assertEquals("Existing stage", stage.getName());
        assertEquals(201, stage.getCompletedCount());
        assertEquals(1_000, stage.getMedianDurationMs());
        verify(dao, times(3)).listEvents(eq(9L), argThat(ids -> !ids.isEmpty() && ids.size() <= 100));
    }

    @Test
    void recordedNameWinsWhenLaterEventsOnlyHaveCurrentDefinitionFallback() {
        var first = event(1, 1, 10, "step.started", 0);
        first.setStepName("Historical name"); first.setFallbackStepName("Renamed definition");
        var last = event(2, 1, 10, "step.completed", 1_000);
        last.setStepName(null); last.setFallbackStepName("Renamed definition");
        given(List.of(execution(1, 5, "SUCCEEDED", 2_000)), List.of(first, last));
        assertEquals("Historical name", service.get(9, start, end, null, null).getStages().getFirst().getName());
        assertNull(last.getStepName());
    }

    @Test
    void skipsEventsWithoutAnyIdentityInBothPasses() {
        var anonymous = legacy(3, 1, "step.started", 0);
        var blankName = legacy(4, 1, "step.started", 0);
        blankName.setStepName("   ");
        var started = event(1, 1, 10, "step.started", 0);
        var completed = event(2, 1, 10, "step.completed", 4_000);
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(anonymous, blankName, started, completed));
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals(1, stage.getSampleCount());
        assertEquals(1, stage.getCompletedCount());
        assertEquals(4_000, stage.getTotalDurationMs());
    }

    @Test
    void resolvesKeyOnlyEventsToTheirUniqueRecordedId() {
        var started = event(1, 1, 10, "step.started", 0);
        started.setStepKey("review");
        var keyOnly = legacy(2, 1, "step.completed", 5_000);
        keyOnly.setStepKey("review");
        keyOnly.setStepOrder(1);
        keyOnly.setStepName("Recorded stage");
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(started, keyOnly));
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals("5:id:10", stage.getKey());
        assertEquals(1, stage.getSampleCount());
        assertEquals(5_000, stage.getTotalDurationMs());
    }

    @Test
    void degradesKeyOnlyEventsToTheirKeyWhenNoIdIsRecorded() {
        var started = legacy(1, 1, "step.started", 0);
        started.setStepKey("review");
        started.setStepOrder(1);
        started.setStepName("Review");
        var completed = legacy(2, 1, "step.completed", 5_000);
        completed.setStepKey("review");
        completed.setStepOrder(1);
        completed.setStepName("Review");
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(started, completed));
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals("5:key:review", stage.getKey());
        assertEquals("Review", stage.getName());
        assertEquals(5_000, stage.getTotalDurationMs());
    }

    @Test
    void degradesBlankKeyToTheRecordedOrder() {
        var started = legacy(1, 1, "step.started", 0);
        started.setStepKey("   ");
        started.setStepOrder(3);
        started.setStepName("Ordered stage");
        var completed = legacy(2, 1, "step.completed", 7_000);
        completed.setStepKey("   ");
        completed.setStepOrder(3);
        completed.setStepName("Ordered stage");
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(started, completed));
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals("5:order:3", stage.getKey());
        assertEquals(7_000, stage.getTotalDurationMs());
    }

    @Test
    void degradesEventsWithoutKeyOrOrderToTheirRecordedName() {
        var started = legacy(1, 1, "step.started", 0);
        started.setStepName("Legacy stage");
        var completed = legacy(2, 1, "step.completed", 3_000);
        completed.setStepName("Legacy stage");
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(started, completed));
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals("5:name:Legacy stage", stage.getKey());
        assertEquals("Legacy stage", stage.getName());
        assertEquals(3_000, stage.getTotalDurationMs());
    }

    @Test
    void keepsOrderIdentityWhenTheAliasMatchesSeveralRecordedIds() {
        var first = event(1, 1, 10, "step.started", 0);
        var second = event(2, 1, 11, "step.started", 0);
        var legacy = legacy(3, 1, "step.completed", 5_000);
        legacy.setStepOrder(1);
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(first, second, legacy));
        var stages = service.get(9, start, end, null, null).getStages();
        assertEquals(3, stages.size());
        var ambiguous = stages.stream().filter(s -> "5:order:1".equals(s.getKey())).findFirst().orElseThrow();
        assertEquals(1, ambiguous.getSampleCount());
        assertEquals(1, ambiguous.getCompletedCount());
        assertEquals(1, ambiguous.getMissingDurationCount());
    }

    @Test
    void fallsBackToTheDefinitionNameWhenRecordedNamesAreBlank() {
        var started = event(1, 1, 10, "step.started", 0);
        started.setStepKey("  ");
        started.setStepName("   ");
        started.setFallbackStepName("Fallback stage");
        var completed = event(2, 1, 10, "step.completed", 5_000);
        completed.setStepKey("  ");
        completed.setStepName("   ");
        completed.setStepOrder(null);
        given(List.of(execution(1, 5, "SUCCEEDED", 10_000)), List.of(started, completed));
        var stage = service.get(9, start, end, null, null).getStages().getFirst();
        assertEquals("Fallback stage", stage.getName());
        assertEquals(5_000, stage.getTotalDurationMs());
    }

    @Test
    void validatesBoundsAndReturnsEmptyDataWithoutAnEmptyInQuery() {
        assertThrows(KeyedIllegalArgumentException.class, () -> service.get(9, null, end, null, null));
        assertThrows(KeyedIllegalArgumentException.class, () -> service.get(9, start, null, null, null));
        assertThrows(KeyedIllegalArgumentException.class, () -> service.get(9, end, start, null, null));
        assertThrows(KeyedIllegalArgumentException.class,
                () -> service.get(9, start, LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1), null, null));
        LocalDate recentEnd = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        assertThrows(KeyedIllegalArgumentException.class,
                () -> service.get(9, recentEnd.minusDays(366), recentEnd, null, null));
        assertThrows(KeyedIllegalArgumentException.class, () -> service.get(9, start, end, -1L, null));
        assertThrows(KeyedIllegalArgumentException.class, () -> service.get(9, start, end, null, -1L));
        verifyNoInteractions(dao);
        given(List.of(), List.of());
        assertTrue(service.get(9, start, end, null, null).getStages().isEmpty());
        verify(dao, never()).listEvents(anyLong(), anyList());
        given(Collections.nCopies(5001, execution(1, 5, "SUCCEEDED", 0)), List.of());
        var failure = assertThrows(KeyedIllegalArgumentException.class, () -> service.get(9, start, end, null, null));
        assertTrue(failure.getMessage().contains("5000"));
    }

    private void given(List<StageDurationService.Execution> executions, List<Event> events) {
        when(dao.listExecutions(anyLong(), any(), any(), any(), any(), anyInt())).thenReturn(executions);
        when(dao.listEvents(anyLong(), anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(1);
            return events.stream().filter(event -> ids.contains(event.getDispatchId())).toList();
        });
    }

    private StageDurationService.Execution execution(long id, long flow, String status, long updated) {
        var execution = new StageDurationService.Execution();
        execution.setId(id); execution.setWorkitemId(100L); execution.setSdlcId(flow);
        execution.setSdlcName("Flow " + flow); execution.setTitle("Original title");
        execution.setStatus(status); execution.setGmtCreate(new Date(0)); execution.setGmtModified(new Date(updated));
        return execution;
    }

    private Event event(long id, long dispatch, long step, String type, long time) {
        var event = new Event();
        event.setId(id); event.setDispatchId(dispatch); event.setStepId(step); event.setStepOrder(1);
        event.setStepName("Recorded stage"); event.setEventType(type); event.setEventTime(new Date(time));
        return event;
    }

    private Event legacy(long id, long dispatch, String type, long time) {
        var event = event(id, dispatch, 10, type, time);
        event.setStepId(null); event.setStepKey(null); event.setStepOrder(null); event.setStepName(null);
        return event;
    }
}
