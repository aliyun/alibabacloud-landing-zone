package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.insights.dto.HumanAgentParticipationVO;
import com.aliyun.autowonder.insights.dto.HumanAgentSlowTailVO;
import com.aliyun.autowonder.insights.participation.CompletionFactQuery;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationFact;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationProperties;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRefreshService;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import com.aliyun.autowonder.insights.participation.ParticipationRefreshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InsightsParticipationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InsightsDao insightsDao;
    private DispatchAiUsageService usageService;
    private HumanAgentParticipationRefreshService refreshService;
    private HumanAgentParticipationProperties properties;
    private CompletionFactQuery completionFactQuery;
    private InsightsService service;

    @BeforeEach
    void setUp() {
        insightsDao = mock(InsightsDao.class);
        usageService = mock(DispatchAiUsageService.class);
        refreshService = mock(HumanAgentParticipationRefreshService.class);
        properties = new HumanAgentParticipationProperties();
        completionFactQuery = new CompletionFactQuery(refreshService, properties);
        service = new InsightsService(insightsDao, usageService, refreshService, properties, completionFactQuery);
    }

    @Test
    void getParticipationReturnsUnavailableWhenNoCache() {
        when(refreshService.read(1L)).thenReturn(Optional.empty());
        when(refreshService.requestRefresh(1L)).thenReturn(true);

        HumanAgentParticipationVO vo = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

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
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

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
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertTrue(vo.isAvailable());
        assertFalse(vo.isRefreshTriggered());
        assertEquals(3, vo.getSampleSize());
        assertNotNull(vo.getAverage());
        assertEquals(5400, vo.getAverage().getTotalDurationSeconds());
        assertEquals(2700, vo.getAverage().getHumanDurationSeconds());
        assertEquals(2700, vo.getAverage().getAgentDurationSeconds());
        assertEquals(90, vo.getAverageTotalMinutes());
        assertFalse(vo.isRealtimeTail());
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
                        LocalDate.of(2026, 7, 1)));

        assertThrows(IllegalArgumentException.class, () ->
                service.getParticipation(1L,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.now(ZONE).plusDays(1)));
    }

    @Test
    void getParticipationMergesTodayRealtimeTailAndUsesSharedAverageCaliber() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        Instant yesterdayNoon = yesterday.atTime(12, 0).atZone(ZONE).toInstant();
        Instant todayMidnight = today.atStartOfDay(ZONE).toInstant();
        Instant todayMorning = today.atTime(9, 0).atZone(ZONE).toInstant();
        HumanAgentParticipationFact yesterdayOne = fact(1, "Yesterday A", yesterdayNoon, 3600);
        HumanAgentParticipationFact yesterdayTwo = fact(2, "Yesterday B", yesterdayNoon.plusSeconds(60), 5400);
        HumanAgentParticipationFact boundary = fact(3, "Midnight", todayMidnight, 1800);
        HumanAgentParticipationFact todayFact = fact(4, "Today", todayMorning, 2700);
        HumanAgentParticipationFact excludedToday = new HumanAgentParticipationFact(
                5, "Broken chain", todayMorning, 0, 0, 0);
        excludedToday.setExclusionReason("MISSING_CREATE");
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(
                yesterday.atTime(3, 0).atZone(ZONE).toInstant().toString(), yesterday.toString(),
                List.of(yesterdayOne, yesterdayTwo))));
        when(refreshService.todayFacts(1L)).thenReturn(List.of(boundary, todayFact, excludedToday));

        HumanAgentParticipationVO vo = service.getParticipation(1L, yesterday, today);

        assertTrue(vo.isAvailable());
        assertTrue(vo.isRealtimeTail());
        assertEquals(today.toString(), vo.getDataThrough());
        assertEquals(4, vo.getSampleSize());
        assertEquals(5, vo.getIdentifiedCompletedCount());
        assertEquals(1, vo.getExclusions().get("MISSING_CREATE"));
        // (3600 + 5400 + 1800 + 2700) / 4 = 3375s -> 56 分钟，与流水线健康共用同一算法。
        assertEquals(56, vo.getAverageTotalMinutes());
        assertEquals(2, vo.getTrend().size());
        assertEquals(2, vo.getTrend().get(0).getSampleSize());
        assertEquals(2, vo.getTrend().get(1).getSampleSize());
    }

    @Test
    void getParticipationDedupesWorkitemsSeenInBothSnapshotAndTail() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        HumanAgentParticipationFact snapshotFact = fact(7, "Snapshot copy", yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 600);
        HumanAgentParticipationFact tailDuplicate = fact(7, "Tail copy", today.atTime(1, 0).atZone(ZONE).toInstant(), 9999);
        HumanAgentParticipationFact tailNew = fact(8, "Tail only", today.atTime(2, 0).atZone(ZONE).toInstant(), 1800);
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(
                Instant.now().toString(), yesterday.toString(), List.of(snapshotFact))));
        when(refreshService.todayFacts(1L)).thenReturn(List.of(tailDuplicate, tailNew));

        HumanAgentParticipationVO vo = service.getParticipation(1L, yesterday, today);

        assertEquals(2, vo.getSampleSize());
        // (600 + 1800) / 2 = 1200s -> 20 分钟；重复 workitem 保留快照事实，不被尾覆盖。
        assertEquals(20, vo.getAverageTotalMinutes());
    }

    @Test
    void getParticipationWithTodayInRangeButEmptyTailReportsRealtimeWindow() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(
                Instant.now().toString(), yesterday.toString(),
                List.of(fact(1, "Old", yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 3600)))));
        when(refreshService.todayFacts(1L)).thenReturn(Collections.emptyList());

        HumanAgentParticipationVO vo = service.getParticipation(1L, today, today);

        assertTrue(vo.isAvailable());
        assertTrue(vo.isRealtimeTail());
        assertEquals(today.toString(), vo.getDataThrough());
        assertEquals(0, vo.getSampleSize());
        assertNull(vo.getAverageTotalMinutes());
    }

    @Test
    void getSlowTailIncludesTodayRealtimeTail() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(
                Instant.now().toString(), yesterday.toString(),
                List.of(fact(1, "Yesterday slow", yesterday.atTime(10, 0).atZone(ZONE).toInstant(), 1000)))));
        when(refreshService.todayFacts(1L)).thenReturn(List.of(
                fact(2, "Today slow", today.atTime(9, 0).atZone(ZONE).toInstant(), 9000)));

        HumanAgentSlowTailVO vo = service.getSlowTail(1L, yesterday, today);

        assertEquals(2, vo.getItems().size());
        assertEquals(2L, vo.getItems().get(0).getWorkitemId());
        assertEquals(9000, vo.getItems().get(0).getTotalDurationSeconds());
        assertEquals(1L, vo.getItems().get(1).getWorkitemId());
        assertEquals(1000, vo.getItems().get(1).getTotalDurationSeconds());
    }

    private static HumanAgentParticipationFact fact(long id, String title, Instant completedAt, long totalSeconds) {
        return new HumanAgentParticipationFact(id, title, completedAt, totalSeconds, totalSeconds / 2, totalSeconds / 2);
    }

    @Test
    void getSlowTailReturnsTheTenLongestWorkitemsAcrossTheWholeRange() {
        Instant completedAt = Instant.parse("2026-07-15T10:00:00Z");
        List<HumanAgentParticipationFact> facts = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            facts.add(new HumanAgentParticipationFact(i, "w" + i, completedAt.plusSeconds(i),
                    i * 1000L, i * 500L, i * 500L));
        }
        ParsedSnapshot snapshot = new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", facts);
        when(refreshService.read(1L)).thenReturn(Optional.of(snapshot));

        HumanAgentSlowTailVO vo = service.getSlowTail(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertEquals(10, vo.getItems().size());
        assertEquals(12L, vo.getItems().get(0).getWorkitemId());
        assertEquals(12000, vo.getItems().get(0).getTotalDurationSeconds());
        assertEquals(3L, vo.getItems().get(9).getWorkitemId());
    }

    @Test
    void getSlowTailReturnsEmptyWhenNoCache() {
        when(refreshService.read(1L)).thenReturn(Optional.empty());

        HumanAgentSlowTailVO vo = service.getSlowTail(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

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
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

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
        first.setAgentParticipated(true);
        var second = new HumanAgentParticipationFact(2, "B", day.plusSeconds(86400 * 2), 2, 0, 2);
        var missing = new HumanAgentParticipationFact(3, "C", day, 0, 0, 0);
        missing.setExclusionReason("MISSING_CREATE");
        var outside = new HumanAgentParticipationFact(4, "D", day.minusSeconds(86400), 0, 0, 0);
        outside.setExclusionReason("MISSING_CREATE");
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(day.toString(), "2026-07-31",
                List.of(first, second, missing, outside))));
        var vo = service.getParticipation(1L, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12));
        assertEquals(2, vo.getSampleSize());
        assertEquals(3, vo.getIdentifiedCompletedCount());
        assertEquals(1, vo.getExclusions().get("MISSING_CREATE"));
        assertEquals(1.5, vo.getMedianTotalSeconds());
        assertEquals(1.0 / 3, vo.getAverage().getHumanShare(), 0.000001);
        assertEquals(2, vo.getAgentWorkitemCount());
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
            if(slowTail) service.getSlowTail(1L,LocalDate.of(2026,8,1),LocalDate.of(2026,7,1));
            else service.getParticipation(1L,LocalDate.of(2026,8,1),LocalDate.of(2026,7,1));
        });
        assertEquals("Invalid date range: start_date must be <= end_date and end_date <= today",ex.getMessage());
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
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rejectsRangesWiderThanTheInclusive180DayWindow(boolean slowTail) {
        when(refreshService.read(1L)).thenReturn(Optional.of(
                new ParsedSnapshot("2026-08-05T03:00:00Z", "2026-08-04", List.of())));
        LocalDate end = LocalDate.now(ZONE).minusDays(1);
        LocalDate start = end.minusDays(181);

        var ex = assertThrows(IllegalArgumentException.class, () -> {
            if (slowTail) service.getSlowTail(1L, start, end);
            else service.getParticipation(1L, start, end);
        });

        assertEquals("Invalid date range: start_date must be <= end_date and end_date <= today", ex.getMessage());
        assertEquals(InsightsService.MAX_PARTICIPATION_RANGE_DAYS, 180);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void acceptsTheInclusive180DayWindow(boolean slowTail) {
        LocalDate end = LocalDate.now(ZONE).minusDays(1);
        LocalDate start = end.minusDays(180);
        when(refreshService.read(1L)).thenReturn(Optional.of(
                new ParsedSnapshot(Instant.now().toString(), end.toString(), List.of())));

        assertDoesNotThrow(() -> {
            if (slowTail) service.getSlowTail(1L, start, end);
            else service.getParticipation(1L, start, end);
        });
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

    @Test
    void filtersParticipationSummaryAndSlowTailByWorkType() {
        Instant completedAt = Instant.parse("2026-07-15T10:00:00Z");
        HumanAgentParticipationFact requirement = fact(1, "Req A", completedAt, 3600);
        requirement.setWorkType("REQ");
        HumanAgentParticipationFact bug = fact(2, "Bug B", completedAt.plusSeconds(60), 7200);
        bug.setWorkType("BUG");
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(
                "2026-08-05T03:00:00Z", "2026-08-04", List.of(requirement, bug))));

        HumanAgentParticipationVO participation = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "REQ");
        assertEquals(1, participation.getSampleSize());
        assertEquals(60, participation.getAverageTotalMinutes());
        assertEquals(1L, participation.getP90().getWorkitemId());

        HumanAgentSlowTailVO slowTail = service.getSlowTail(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "bug");
        assertEquals(1, slowTail.getItems().size());
        assertEquals(2L, slowTail.getItems().get(0).getWorkitemId());

        HumanAgentParticipationVO all = service.getParticipation(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "ALL");
        assertEquals(2, all.getSampleSize());
    }

    @Test
    void restEndpointsForwardTheWorkTypeFilter() throws Exception {
        Instant completedAt = Instant.parse("2026-07-15T10:00:00Z");
        HumanAgentParticipationFact requirement = fact(1, "Req A", completedAt, 3600);
        requirement.setWorkType("REQ");
        HumanAgentParticipationFact task = fact(2, "Task B", completedAt.plusSeconds(60), 7200);
        task.setWorkType("TASK");
        when(refreshService.read(1L)).thenReturn(Optional.of(new ParsedSnapshot(
                "2026-08-05T03:00:00Z", "2026-08-04", List.of(requirement, task))));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new InsightsController(service)).build();
        com.aliyun.autowonder.context.AutoWonderContext.get().setCurrentWorkspaceId(1L);
        try {
            var participationResponse = mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/insights/human-agent-participation")
                            .param("start_date", "2026-07-01")
                            .param("end_date", "2026-07-31")
                            .param("work_type", "TASK"))
                    .andReturn().getResponse();
            var participationBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(participationResponse.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(participationBody.path("success").asBoolean());
            assertEquals(1, participationBody.path("data").path("sampleSize").asInt());
            assertEquals(120, participationBody.path("data").path("averageTotalMinutes").asInt());

            var slowTailResponse = mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/insights/human-agent-participation/slowest")
                            .param("start_date", "2026-07-01")
                            .param("end_date", "2026-07-31")
                            .param("work_type", "REQ"))
                    .andReturn().getResponse();
            var slowTailBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(slowTailResponse.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(slowTailBody.path("success").asBoolean());
            assertEquals(1, slowTailBody.path("data").path("items").size());
            assertEquals(1L, slowTailBody.path("data").path("items").get(0).path("workitemId").asLong());

            var unfiltered = mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/insights/human-agent-participation")
                            .param("start_date", "2026-07-01")
                            .param("end_date", "2026-07-31"))
                    .andReturn().getResponse();
            var unfilteredBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(unfiltered.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, unfilteredBody.path("data").path("sampleSize").asInt());
        } finally {
            com.aliyun.autowonder.context.AutoWonderContext.destroy();
        }
    }

    @Test
    void forceParticipationRefreshForwardsTheRefreshResult() {
        when(refreshService.forceRefresh(1L)).thenReturn(ParticipationRefreshResult.LOCK_CONTENTION);

        assertEquals(ParticipationRefreshResult.LOCK_CONTENTION, service.forceParticipationRefresh(1L));
        verify(refreshService).forceRefresh(1L);
    }

    @Test
    void restForceRefreshDistinguishesAcceptedLockContentionAndQueueFull() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new InsightsController(service)).build();
        com.aliyun.autowonder.context.AutoWonderContext.get().setCurrentWorkspaceId(1L);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(refreshService.forceRefresh(1L)).thenReturn(
                ParticipationRefreshResult.ACCEPTED,
                ParticipationRefreshResult.LOCK_CONTENTION,
                ParticipationRefreshResult.QUEUE_FULL);
        try {
            var accepted = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/insights/human-agent-participation/refresh"))
                    .andReturn().getResponse();
            assertEquals(200, accepted.getStatus());
            assertTrue(mapper.readTree(accepted.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .path("success").asBoolean());

            var contended = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/insights/human-agent-participation/refresh"))
                    .andReturn().getResponse();
            assertEquals(200, contended.getStatus());
            var contendedBody = mapper.readTree(contended.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(contendedBody.path("success").asBoolean());
            assertEquals("10430", contendedBody.path("code").asText());

            var queueFull = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/insights/human-agent-participation/refresh"))
                    .andReturn().getResponse();
            assertEquals(200, queueFull.getStatus());
            var queueFullBody = mapper.readTree(queueFull.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(queueFullBody.path("success").asBoolean());
            assertEquals("10431", queueFullBody.path("code").asText());
        } finally {
            com.aliyun.autowonder.context.AutoWonderContext.destroy();
        }
    }
}
