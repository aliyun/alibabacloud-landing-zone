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
    @Test
    void reportsCoverageMedianAndRatiosFromSumsAndKeepsEmptyDaysDistinct() {
        Instant day = Instant.parse("2026-07-10T00:00:00Z");
        var first = new HumanAgentParticipationFact(1, "A", day, 1, 1, 0);
        first.setExecution(0L, "COMPLETE");
        first.setAgentParticipated(true);
        var second = new HumanAgentParticipationFact(2, "B", day.plusSeconds(86400 * 2), 2, 0, 2);
        var missing = new HumanAgentParticipationFact(3, "C", day, 0, 0, 0);
        missing.setExclusionReason("MISSING_CREATE");
        var outside = new HumanAgentParticipationFact(4, "D", day.minusSeconds(86400), 0, 0, 0);
        outside.setExclusionReason("MISSING_CREATE");
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(day.toString(), "2026-07-31",
                List.of(first, second, missing, outside))));
        var vo = service.getParticipation(1L, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12), "DAY");
        assertEquals(2, vo.getSampleSize());
        assertEquals(3, vo.getIdentifiedCompletedCount());
        assertEquals(1, vo.getExclusions().get("MISSING_CREATE"));
        assertEquals(1.5, vo.getMedianTotalSeconds());
        assertEquals(1.0 / 3, vo.getAverage().getHumanShare(), 0.000001);
        assertEquals(2, vo.getAgentWorkitemCount());
        assertEquals(1, vo.getExecutionSampleSize());
        assertEquals(0.0, vo.getAverageExecutionSeconds());
        assertEquals(1, vo.getExecutionMissingCount());
        assertEquals(3, vo.getTrend().size());
        assertEquals(0, vo.getTrend().get(1).getSampleSize());
        assertNull(vo.getTrend().get(1).getHumanShare());
        assertEquals(1, vo.getTrend().get(2).getSampleSize());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void invalidRangeRetainsIllegalArgDiagnosticAndGlobal200Boundary(boolean slowTail) {
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot("2026-08-05T03:00:00Z","2026-08-04",List.of())));
        var ex=assertThrows(IllegalArgumentException.class, () -> {
            if(slowTail) service.getSlowTail(1L,LocalDate.of(2026,8,1),LocalDate.of(2026,7,1),1,20);
            else service.getParticipation(1L,LocalDate.of(2026,8,1),LocalDate.of(2026,7,1),"DAY");
        });
        assertEquals("Invalid date range: start_date must be <= end_date and end_date <= dataThrough",ex.getMessage());
        assertNull(ex.getCause());
        var handler=new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var zh=handler.handleIllegalArg(ex); assertEquals("10001",zh.getCode());
            assertEquals("日期范围无效：开始日期必须不晚于结束日期，结束日期必须不晚于数据截止日期",zh.getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            assertEquals("Invalid date range: start date must not be after end date, and end date must not be after the data cutoff.",handler.handleIllegalArg(ex).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"zh-CN","en-US"})
    void actualRestRoutesKeep200CodeAndRequestIdentityForDateFailures(String language) throws Exception {
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot("2026-08-05T03:00:00Z","2026-08-04",List.of())));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new InsightsController(service))
                .setControllerAdvice(new com.aliyun.autowonder.common.web.GlobalExceptionHandler()).build();
        com.aliyun.autowonder.context.AutoWonderContext.get().setCurrentWorkspaceId(1L);
        com.aliyun.autowonder.context.AutoWonderContext.get().setRequestId("raw-request");
        try {
            for(String path:List.of("/api/insights/human-agent-participation","/api/insights/human-agent-participation/slowest")) {
                var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .param("start_date","2026-08-01").param("end_date","2026-07-01").header("Accept-Language",language)).andReturn().getResponse();
                assertEquals(200,response.getStatus());
                var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("10001",body.path("code").asText());assertFalse(body.path("success").asBoolean());
                assertEquals("raw-request",body.path("request_id").asText());
                assertEquals(language.equals("en-US") ? "Invalid date range: start date must not be after end date, and end date must not be after the data cutoff." : "日期范围无效：开始日期必须不晚于结束日期，结束日期必须不晚于数据截止日期",body.path("message").asText());
            }
        } finally { com.aliyun.autowonder.context.AutoWonderContext.destroy(); }
    }
}
