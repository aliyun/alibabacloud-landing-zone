package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.insights.dto.HumanAgentParticipationVO;
import com.aliyun.autowonder.insights.dto.HumanAgentSlowTailPageVO;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationFact;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationProperties;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRefreshService;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InsightsParticipationServiceTest {

    private InsightsDao insightsDao;
    private DispatchAiUsageService usageService;
    private HumanAgentParticipationRefreshService refreshService;
    private HumanAgentParticipationProperties properties;
    private InsightsService service;

    @BeforeEach
    void setUp() {
        insightsDao = mock(InsightsDao.class);
        usageService = mock(DispatchAiUsageService.class);
        refreshService = mock(HumanAgentParticipationRefreshService.class);
        properties = new HumanAgentParticipationProperties();
        service = new InsightsService(insightsDao, usageService, refreshService, properties);
    }

    @Test
    void getParticipationReturnsUnavailableWhenNoCache() {
        when(refreshService.read(1L)).thenReturn(Optional.empty());
        when(refreshService.requestRefresh(1L)).thenReturn(true);

        HumanAgentParticipationVO vo = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "DAY");

        assertFalse(vo.isAvailable());
        assertTrue(vo.isRefreshTriggered());
        verify(refreshService).requestRefresh(1L);
    }

    @Test
    void getParticipationServesDataAfterWaitCompletesRefresh() {
        Instant completedAt = Instant.parse("2026-07-15T10:00:00Z");
        List<HumanAgentParticipationFact> facts = Arrays.asList(
                new HumanAgentParticipationFact(1L, "Task A", completedAt, 3600, 1800, 1800)
        );
        ParsedSnapshot snapshot = new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", facts);
        when(refreshService.read(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(snapshot));
        when(refreshService.requestRefresh(1L)).thenReturn(true);
        when(refreshService.waitForRefresh(eq(1L), anyLong())).thenReturn(true);

        HumanAgentParticipationVO vo = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "DAY");

        assertTrue(vo.isAvailable());
        assertFalse(vo.isRefreshTriggered());
        assertEquals(1, vo.getSampleSize());
        verify(refreshService).waitForRefresh(eq(1L), anyLong());
    }

    @Test
    void getParticipationReturnsSummaryFromCache() {
        Instant completedAt = Instant.parse("2026-07-15T10:00:00Z");
        List<HumanAgentParticipationFact> facts = Arrays.asList(
                new HumanAgentParticipationFact(1L, "Task A", completedAt, 3600, 1800, 1800),
                new HumanAgentParticipationFact(2L, "Task B", completedAt, 7200, 3600, 3600),
                new HumanAgentParticipationFact(3L, "Task C", completedAt, 5400, 2700, 2700)
        );
        ParsedSnapshot snapshot = new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", facts);
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot));

        HumanAgentParticipationVO vo = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "DAY");

        assertTrue(vo.isAvailable());
        assertFalse(vo.isRefreshTriggered());
        assertEquals(3, vo.getSampleSize());
        assertNotNull(vo.getAverage());
        assertEquals(5400, vo.getAverage().getTotalDurationSeconds());
        assertEquals(2700, vo.getAverage().getHumanDurationSeconds());
        assertEquals(2700, vo.getAverage().getAgentDurationSeconds());
        assertNotNull(vo.getP90());
        verify(refreshService, never()).requestRefresh(anyLong());
    }

    @Test
    void getParticipationThrowsOnInvalidDateRange() {
        ParsedSnapshot snapshot = new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", Collections.emptyList());
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot));

        assertThrows(IllegalArgumentException.class, () ->
                service.getParticipation(1L,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 7, 1), "DAY"));

        assertThrows(IllegalArgumentException.class, () ->
                service.getParticipation(1L,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 5), "DAY"));
    }

    @Test
    void getSlowTailReturnsPaginatedResults() {
        Instant completedAt = Instant.parse("2026-07-15T10:00:00Z");
        List<HumanAgentParticipationFact> facts = Arrays.asList(
                new HumanAgentParticipationFact(1L, "A", completedAt, 1000, 500, 500),
                new HumanAgentParticipationFact(2L, "B", completedAt, 2000, 1000, 1000),
                new HumanAgentParticipationFact(3L, "C", completedAt, 3000, 1500, 1500),
                new HumanAgentParticipationFact(4L, "D", completedAt, 4000, 2000, 2000),
                new HumanAgentParticipationFact(5L, "E", completedAt, 5000, 2500, 2500),
                new HumanAgentParticipationFact(6L, "F", completedAt, 6000, 3000, 3000),
                new HumanAgentParticipationFact(7L, "G", completedAt, 7000, 3500, 3500),
                new HumanAgentParticipationFact(8L, "H", completedAt, 8000, 4000, 4000),
                new HumanAgentParticipationFact(9L, "I", completedAt, 9000, 4500, 4500),
                new HumanAgentParticipationFact(10L, "J", completedAt, 10000, 5000, 5000)
        );
        ParsedSnapshot snapshot = new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", facts);
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot));

        HumanAgentSlowTailPageVO vo = service.getSlowTail(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 1, 20);

        assertEquals(1, vo.getTailSize());
        assertEquals(1, vo.getItems().size());
        assertEquals(10L, vo.getItems().get(0).getWorkitemId());
        assertEquals(10000, vo.getItems().get(0).getTotalDurationSeconds());
    }

    @Test
    void getSlowTailReturnsEmptyWhenNoCache() {
        when(refreshService.read(1L)).thenReturn(Optional.empty());

        HumanAgentSlowTailPageVO vo = service.getSlowTail(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 1, 20);

        assertEquals(0, vo.getTailSize());
        assertTrue(vo.getItems().isEmpty());
        verify(refreshService).requestRefresh(1L);
    }

    @Test
    void getParticipationHandlesEmptyFactsInRange() {
        Instant completedAt = Instant.parse("2026-06-15T10:00:00Z");
        List<HumanAgentParticipationFact> facts = Collections.singletonList(
                new HumanAgentParticipationFact(1L, "Old Task", completedAt, 3600, 1800, 1800)
        );
        ParsedSnapshot snapshot = new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", facts);
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot));

        HumanAgentParticipationVO vo = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "DAY");

        assertTrue(vo.isAvailable());
        assertEquals(0, vo.getSampleSize());
        assertNotNull(vo.getAverage());
        assertEquals(0, vo.getAverage().getTotalDurationSeconds());
        assertNull(vo.getP90());
        assertTrue(vo.getTrend().isEmpty());
    }
}
