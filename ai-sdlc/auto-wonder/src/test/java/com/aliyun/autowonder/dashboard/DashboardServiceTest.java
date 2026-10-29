package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dashboard.dto.CostCompositionVO;
import com.aliyun.autowonder.dashboard.dto.CostKpiVO;
import com.aliyun.autowonder.dashboard.dto.OverviewVO;
import com.aliyun.autowonder.dashboard.dto.PipelineHealthVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.dashboard.dto.RunningWorkitemVO;
import com.aliyun.autowonder.insights.InsightsDao;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.participation.CompletionFactQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private DashboardDao dao;
    private InsightsDao insightsDao;
    private CompletionFactQuery completionFactQuery;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        dao = mock(DashboardDao.class);
        insightsDao = mock(InsightsDao.class);
        completionFactQuery = mock(CompletionFactQuery.class);
        service = new DashboardService(dao, insightsDao, completionFactQuery, "FACT");
    }

    @Test
    void avgLoadIsRunningDividedByOnlineAgents() {
        when(dao.countRunningDispatches(1L)).thenReturn(7);
        when(dao.countOnlineAgents(1L)).thenReturn(5);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(1.4, vo.getKpi().getAvgLoad(), 0.001);
    }

    @Test
    void kpiIncludesWorkshopOverviewCompletedTaskMetrics() {
        when(dao.countTodayCompletedTasks(1L)).thenReturn(12);
        when(dao.countWeekCompletedTasks(1L)).thenReturn(57);
        when(dao.avgTodayCompletedTaskDurationMinutes(1L)).thenReturn(34);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(12, vo.getKpi().getTodayCompletedTasks());
        assertEquals(57, vo.getKpi().getWeekCompletedTasks());
        assertEquals(34, vo.getKpi().getAvgTaskDurationMinutes());
    }

    @Test
    void kpiAvgTaskDurationFallsBackToZeroWhenNoCompletedTasks() {
        when(dao.avgTodayCompletedTaskDurationMinutes(1L)).thenReturn(null);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(0, vo.getKpi().getAvgTaskDurationMinutes());
    }

    @Test
    void avgLoadIsZeroWhenNoOnlineAgents() {
        when(dao.countRunningDispatches(1L)).thenReturn(3);
        when(dao.countOnlineAgents(1L)).thenReturn(0);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(0.0, vo.getKpi().getAvgLoad(), 0.001);
    }

    @Test
    void successRateGuardsDivideByZero() {
        when(dao.countTodaySucceeded(1L)).thenReturn(0);
        when(dao.countTodayFailedOrTimeout(1L)).thenReturn(0);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(0.0, vo.getHealth().getSuccessRate(), 0.001);
    }

    @Test
    void successRateComputedFromSucceededOverTerminal() {
        // succeeded=9, failedOrTimeout=1 => 9/10 = 90.0
        when(dao.countTodaySucceeded(1L)).thenReturn(9);
        when(dao.countTodayFailedOrTimeout(1L)).thenReturn(1);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(90.0, vo.getHealth().getSuccessRate(), 0.001);
    }

    @Test
    void squadLoadGuardsDivideByZeroAndComputesRatio() {
        when(dao.squadLineAggregates(1L)).thenReturn(List.of(
                Map.of("squadId", 10L, "name", "A", "members", 0L, "online", 0L, "busy", 0L, "runningTasks", 0L),
                Map.of("squadId", 11L, "name", "B", "members", 4L, "online", 3L, "busy", 2L, "runningTasks", 6L)
        ));
        when(dao.squadInProgressWorkitems(1L)).thenReturn(List.of(
                Map.of("squadId", 11L, "cnt", 8L)
        ));
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(2, vo.getSquads().size());
        assertEquals(0.0, vo.getSquads().get(0).getLoad(), 0.001); // members=0 guard (A)
        assertEquals(1.5, vo.getSquads().get(1).getLoad(), 0.001); // 6/4 (B)
        assertEquals(8, vo.getSquads().get(1).getInProgressWorkitems());
        assertEquals(0, vo.getSquads().get(0).getInProgressWorkitems());
    }

    @Test
    void workstationBusyIsTrueWhenRunningTasksPositive() {
        when(dao.onlineWorkstations(1L)).thenReturn(List.of(
                Map.of("agentId", 20L, "name", "全栈-A", "avatarUrl", "", "runningTasks", 2L),
                Map.of("agentId", 21L, "name", "测试-C", "avatarUrl", "", "runningTasks", 0L)
        ));
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertTrue(vo.getWorkstations().get(0).isBusy());
        assertFalse(vo.getWorkstations().get(1).isBusy());
    }

    @Test
    void inventoryMapsCategoriesAndTypes() {
        when(dao.countWorkitemsByLifecycle(1L)).thenReturn(List.of(
                Map.of("category", "INIT", "cnt", 9L),
                Map.of("category", "IN_PROGRESS", "cnt", 18L),
                Map.of("category", "DONE", "cnt", 32L)
        ));
        when(dao.countWorkitemsByType(1L)).thenReturn(List.of(
                Map.of("workType", "REQ", "cnt", 12L),
                Map.of("workType", "TASK", "cnt", 41L),
                Map.of("workType", "BUG", "cnt", 6L)
        ));
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(9, vo.getInventory().getByLifecycle().getInit());
        assertEquals(18, vo.getInventory().getByLifecycle().getInProgress());
        assertEquals(32, vo.getInventory().getByLifecycle().getDone());
        assertEquals(0, vo.getInventory().getByLifecycle().getCanceled());
        assertEquals(12, vo.getInventory().getByType().getReq());
        assertEquals(41, vo.getInventory().getByType().getTask());
        assertEquals(6, vo.getInventory().getByType().getBug());
    }

    @Test
    void generatedAtIsAnInstantExpressingTheRealGenerationMoment() {
        Instant before = Instant.now();
        RealtimeDashboardVO vo = service.getRealtime(1L);
        Instant after = Instant.now();
        Instant generatedAt = Instant.parse(vo.getGeneratedAt());
        assertFalse(generatedAt.isBefore(before), "generatedAt 不得早于调用前采样时刻");
        assertFalse(generatedAt.isAfter(after), "generatedAt 不得晚于调用后采样时刻");
    }

    @Test
    void generatedAtCarriesExplicitUtcDesignatorInsteadOfBareWallClock() {
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertTrue(vo.getGeneratedAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"),
                "generatedAt 必须是带 Z 明确时区的 ISO-8601 instant，当前值: " + vo.getGeneratedAt());
    }

    @Test
    void getAgentRunningValidatesTenantMembership() {
        when(dao.agentExists(1L, 99L)).thenReturn(0);
        assertThrows(BizException.class, () -> service.getAgentRunning(1L, 99L));
    }

    @Test
    void getAgentRunningReturnsListWhenAgentBelongsToTenant() {
        when(dao.agentExists(1L, 5L)).thenReturn(1);
        RunningTaskVO t = new RunningTaskVO();
        t.setDispatchId(100L);
        when(dao.listAgentRunning(1L, 5L)).thenReturn(List.of(t));
        List<RunningTaskVO> result = service.getAgentRunning(1L, 5L);
        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getDispatchId());
    }

    @Test
    void overviewReturnsOnlyTheFourWorkitemLevelKpisAndInventory() {
        when(dao.countTodayCreatedWorkitems(1L)).thenReturn(6);
        when(dao.countRunningWorkitems(1L)).thenReturn(1);
        when(dao.countTodayCompletedTasks(1L)).thenReturn(4);
        when(dao.countWeekCompletedTasks(1L)).thenReturn(59);
        when(dao.countWorkitemsByLifecycle(1L)).thenReturn(List.of(
                Map.of("category", "INIT", "cnt", 90L),
                Map.of("category", "IN_PROGRESS", "cnt", 93L),
                Map.of("category", "DONE", "cnt", 469L),
                Map.of("category", "CANCELED", "cnt", 23L)
        ));
        when(dao.countWorkitemsByType(1L)).thenReturn(List.of(
                Map.of("workType", "REQ", "cnt", 504L),
                Map.of("workType", "BUG", "cnt", 155L),
                Map.of("workType", "TASK", "cnt", 16L)
        ));

        OverviewVO vo = service.getOverview(1L);

        assertEquals(6, vo.getKpi().getTodayCreatedWorkitems());
        assertEquals(1, vo.getKpi().getRunningWorkitems());
        assertEquals(4, vo.getKpi().getTodayCompletedTasks());
        assertEquals(59, vo.getKpi().getWeekCompletedTasks());
        assertEquals(90, vo.getInventory().getByLifecycle().getInit());
        assertEquals(93, vo.getInventory().getByLifecycle().getInProgress());
        assertEquals(469, vo.getInventory().getByLifecycle().getDone());
        assertEquals(23, vo.getInventory().getByLifecycle().getCanceled());
        assertEquals(504, vo.getInventory().getByType().getReq());
        assertEquals(155, vo.getInventory().getByType().getBug());
        assertEquals(16, vo.getInventory().getByType().getTask());
        assertTrue(vo.getGeneratedAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"));
        verify(dao, never()).countInProgressWorkitems(1L);
        verify(dao, never()).countActiveSquads(1L);
        verify(dao, never()).listRunningFeed(1L, 10);
        verify(dao, never()).countRunningDispatches(1L);
        verify(dao, never()).countQueuedDispatches(1L);
        verify(dao, never()).countOnlineAgents(1L);
    }

    @Test
    void runningWorkitemsDetailKeepsOneRowPerWorkitem() {
        RunningWorkitemVO row = new RunningWorkitemVO();
        row.setWorkitemId(9L);
        row.setTitle("双执行工单");
        row.setRunningDispatchCount(2);
        row.setRunningMinutes(37);
        when(dao.listRunningWorkitems(1L)).thenReturn(List.of(row));

        List<RunningWorkitemVO> result = service.getRunningWorkitems(1L);

        assertEquals(1, result.size());
        assertEquals(9L, result.get(0).getWorkitemId());
        assertEquals(2, result.get(0).getRunningDispatchCount());
    }

    @Test
    void pipelineWindowCoversNaturalDaysInShanghaiTime() {
        LocalDate day = LocalDate.of(2026, 10, 21);

        assertWindow(DashboardService.pipelineWindow("today", day),
                LocalDateTime.of(2026, 10, 21, 0, 0), LocalDateTime.of(2026, 10, 22, 0, 0));
        assertWindow(DashboardService.pipelineWindow("7d", day),
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 21, 0, 0));
        assertWindow(DashboardService.pipelineWindow("30d", day),
                LocalDateTime.of(2026, 9, 21, 0, 0), LocalDateTime.of(2026, 10, 21, 0, 0));
        assertWindow(DashboardService.pipelineWindow("90d", day),
                LocalDateTime.of(2026, 7, 23, 0, 0), LocalDateTime.of(2026, 10, 21, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DashboardService.pipelineWindow("14d", day));

        DashboardService.PipelineWindow today = DashboardService.pipelineWindow("today", day);
        assertEquals(LocalDate.of(2026, 10, 21), today.startDate());
        assertEquals(LocalDate.of(2026, 10, 21), today.endDateInclusive());
        DashboardService.PipelineWindow week = DashboardService.pipelineWindow("7d", day);
        assertEquals(LocalDate.of(2026, 10, 14), week.startDate());
        assertEquals(LocalDate.of(2026, 10, 20), week.endDateInclusive());
    }

    @Test
    void pipelineHealthComputesRatesAndReadsAverageFromTheSharedFactLayer() {
        when(dao.pipelineExecutionStats(eq(1L), any(), any()))
                .thenReturn(Map.of("succeeded", 198L, "total", 201L, "failedOrTimeout", 2L));
        when(dao.pipelineReworkStats(eq(1L), any(), any()))
                .thenReturn(Map.of("reworkedWorkitems", 18L, "executedWorkitems", 142L, "retryCount", 31L));
        when(completionFactQuery.averageMinutes(eq(1L), any(), any())).thenReturn(3738);

        PipelineHealthVO vo = service.getPipelineHealth(1L, "7d");

        assertEquals("7d", vo.getRange());
        assertEquals(98.5, vo.getSuccessRate(), 0.001);
        assertEquals(198, vo.getSuccessSub().getSucceeded());
        assertEquals(201, vo.getSuccessSub().getTotal());
        assertEquals(2, vo.getFailedOrTimeout());
        assertEquals(12.7, vo.getRework().getRate(), 0.001);
        assertEquals(18, vo.getRework().getReworkedWorkitems());
        assertEquals(142, vo.getRework().getExecutedWorkitems());
        assertEquals(31, vo.getRework().getRetryCount());
        assertEquals(3738, vo.getAvgWorkitemDurationMinutes());
        assertTrue(vo.getGeneratedAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"));

        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ends = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dao).pipelineExecutionStats(eq(1L), starts.capture(), ends.capture());
        assertEquals(0, starts.getValue().getHour());
        assertEquals(0, ends.getValue().getHour());
        assertEquals(7, ChronoUnit.DAYS.between(starts.getValue(), ends.getValue()));
        verify(dao).pipelineReworkStats(1L, starts.getValue(), ends.getValue());

        ArgumentCaptor<LocalDate> startDates = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> endDates = ArgumentCaptor.forClass(LocalDate.class);
        verify(completionFactQuery).averageMinutes(eq(1L), startDates.capture(), endDates.capture());
        assertEquals(starts.getValue().toLocalDate(), startDates.getValue());
        assertEquals(ends.getValue().toLocalDate().minusDays(1), endDates.getValue());
        verify(dao, never()).avgPipelineWorkitemDurationMinutes(anyLong(), any(), any());
    }

    @Test
    void participationAndPipelineShareTheSameFactWindowMapping() {
        when(dao.pipelineExecutionStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(dao.pipelineReworkStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(completionFactQuery.averageMinutes(eq(1L), any(), any())).thenReturn(0);

        service.getPipelineHealth(1L, "today");
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        verify(completionFactQuery).averageMinutes(1L, today, today);

        service.getPipelineHealth(1L, "7d");
        verify(completionFactQuery).averageMinutes(1L, today.minusDays(7), today.minusDays(1));

        service.getPipelineHealth(1L, "30d");
        verify(completionFactQuery).averageMinutes(1L, today.minusDays(30), today.minusDays(1));

        service.getPipelineHealth(1L, "90d");
        verify(completionFactQuery).averageMinutes(1L, today.minusDays(90), today.minusDays(1));
    }

    @Test
    void successAtSwitchFallsBackToTheLegacyDaoAggregate() {
        DashboardService legacy = new DashboardService(dao, insightsDao, completionFactQuery, "SUCCESS_AT");
        when(dao.pipelineExecutionStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(dao.pipelineReworkStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(dao.avgPipelineWorkitemDurationMinutes(eq(1L), any(), any())).thenReturn(120);

        PipelineHealthVO vo = legacy.getPipelineHealth(1L, "7d");

        assertEquals(120, vo.getAvgWorkitemDurationMinutes());
        verify(completionFactQuery, never()).averageMinutes(anyLong(), any(), any());
    }

    @Test
    void unknownAverageSourceFallsBackToTheFactLayer() {
        DashboardService unknown = new DashboardService(dao, insightsDao, completionFactQuery, "unexpected");
        when(dao.pipelineExecutionStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(dao.pipelineReworkStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(completionFactQuery.averageMinutes(eq(1L), any(), any())).thenReturn(42);

        assertEquals(42, unknown.getPipelineHealth(1L, "7d").getAvgWorkitemDurationMinutes());
        verify(dao, never()).avgPipelineWorkitemDurationMinutes(anyLong(), any(), any());
    }

    @Test
    void pipelineHealthUsesDisjointLeftClosedWindowsForEveryRange() {
        when(dao.pipelineExecutionStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(dao.pipelineReworkStats(eq(1L), any(), any())).thenReturn(Map.of());
        when(dao.avgPipelineWorkitemDurationMinutes(eq(1L), any(), any())).thenReturn(null);

        for (String range : List.of("today", "7d", "30d", "90d")) {
            service.getPipelineHealth(1L, range);
        }

        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ends = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dao, times(4)).pipelineExecutionStats(eq(1L), starts.capture(), ends.capture());
        List<String> ranges = List.of("today", "7d", "30d", "90d");
        List<Long> expectedDays = List.of(1L, 7L, 30L, 90L);
        for (int i = 0; i < ranges.size(); i++) {
            LocalDateTime start = starts.getAllValues().get(i);
            LocalDateTime end = ends.getAllValues().get(i);
            assertEquals(0, start.getHour(), ranges.get(i));
            assertEquals(0, end.getHour(), ranges.get(i));
            assertEquals(expectedDays.get(i), ChronoUnit.DAYS.between(start, end), ranges.get(i));
        }
        // 近 N 天不含今日：7d 以上窗口的右界等于今日 00:00，而今日窗口右界是明日 00:00。
        assertEquals(ends.getAllValues().get(1), starts.getAllValues().get(0));
        assertEquals(ends.getAllValues().get(2), starts.getAllValues().get(0));
        assertEquals(ends.getAllValues().get(3), starts.getAllValues().get(0));
    }

    @Test
    void pipelineHealthReturnsNullRatesWhenDenominatorsAreZero() {
        when(dao.pipelineExecutionStats(eq(1L), any(), any()))
                .thenReturn(Map.of("succeeded", 0L, "total", 0L, "failedOrTimeout", 0L));
        when(dao.pipelineReworkStats(eq(1L), any(), any()))
                .thenReturn(Map.of("reworkedWorkitems", 0L, "executedWorkitems", 0L, "retryCount", 0L));
        when(completionFactQuery.averageMinutes(eq(1L), any(), any())).thenReturn(null);

        PipelineHealthVO vo = service.getPipelineHealth(1L, "today");

        assertNull(vo.getSuccessRate());
        assertEquals(0, vo.getSuccessSub().getSucceeded());
        assertEquals(0, vo.getSuccessSub().getTotal());
        assertEquals(0, vo.getFailedOrTimeout());
        assertNull(vo.getRework().getRate());
        assertEquals(0, vo.getRework().getRetryCount());
        assertNull(vo.getAvgWorkitemDurationMinutes());
    }

    @Test
    void pipelineHealthTreatsMissingAggregateRowsAsEmptyWindow() {
        when(dao.pipelineExecutionStats(eq(1L), any(), any())).thenReturn(null);
        when(dao.pipelineReworkStats(eq(1L), any(), any())).thenReturn(null);
        when(dao.avgPipelineWorkitemDurationMinutes(eq(1L), any(), any())).thenReturn(null);

        PipelineHealthVO vo = service.getPipelineHealth(1L, "90d");

        assertNull(vo.getSuccessRate());
        assertEquals(0, vo.getSuccessSub().getTotal());
        assertNull(vo.getRework().getRate());
        assertEquals(0, vo.getRework().getExecutedWorkitems());
    }

    @Test
    void pipelineHealthRoundsRatesToOneDecimal() {
        when(dao.pipelineExecutionStats(eq(1L), any(), any()))
                .thenReturn(Map.of("succeeded", 1L, "total", 3L, "failedOrTimeout", 2L));
        when(dao.pipelineReworkStats(eq(1L), any(), any()))
                .thenReturn(Map.of("reworkedWorkitems", 1L, "executedWorkitems", 3L, "retryCount", 1L));
        when(completionFactQuery.averageMinutes(eq(1L), any(), any())).thenReturn(0);

        PipelineHealthVO vo = service.getPipelineHealth(1L, "30d");

        assertEquals(33.3, vo.getSuccessRate(), 0.001);
        assertEquals(33.3, vo.getRework().getRate(), 0.001);
        assertEquals(0, vo.getAvgWorkitemDurationMinutes());
    }

    @Test
    void pipelineHealthRejectsUnsupportedRangeWithKeyedMessageBeforeTouchingTheDatabase() {
        BizException invalid = assertThrows(BizException.class, () -> service.getPipelineHealth(1L, "14d"));
        assertEquals("dashboard.pipeline_health_range_invalid", invalid.getMessageKey());
        assertEquals("14d", invalid.getMessageArgs()[0]);

        BizException missing = assertThrows(BizException.class, () -> service.getPipelineHealth(1L, null));
        assertEquals("dashboard.pipeline_health_range_invalid", missing.getMessageKey());
        assertEquals("", missing.getMessageArgs()[0]);

        verifyNoInteractions(dao);
    }

    @Test
    void realtimeCostKpiDefaultsToSevenDaysAndAllAgents() {
        when(insightsDao.countTotalCredits(eq(1L), any(Date.class), isNull())).thenReturn(new BigDecimal("11492.65"));
        when(insightsDao.countCreditUsageWorkitems(eq(1L), any(Date.class), isNull())).thenReturn(66);
        when(insightsDao.listCreditsBySource(eq(1L), any(Date.class), isNull())).thenReturn(List.of(
                source("DISPATCH", "11469.66"),
                source("WORKITEM_CLARIFICATION", "11.49"),
                source("PLATFORM_ASSISTANT", "11.50"),
                source("IM", "0"),
                source("OTHER_CONVERSATION", "-2")
        ));

        RealtimeDashboardVO vo = service.getRealtime(1L);

        CostKpiVO cost = vo.getKpi().getCost();
        assertEquals(7, cost.getRange());
        assertEquals("all", cost.getAgentId());
        assertEquals(new BigDecimal("11492.65"), cost.getTotal());
        assertEquals(new BigDecimal("174.13"), cost.getPerTask());
        assertEquals(new BigDecimal("1641.81"), cost.getPerDay());

        CostCompositionVO composition = vo.getCostComposition();
        assertEquals(7, composition.getRange());
        assertEquals("ALL_AGENTS", composition.getScope());
        assertEquals(cost.getTotal(), composition.getTotal());
        assertEquals(List.of("WORKITEM_EXECUTION", "CHIEF_OF_STAFF", "REQUIREMENT_CLARIFY"),
                composition.getSources().stream().map(CostCompositionVO.Source::getKey).toList());
        assertEquals(List.of("工单执行", "平台管家（Chief Of Staff）", "需求澄清"),
                composition.getSources().stream().map(CostCompositionVO.Source::getName).toList());
        assertEquals(new BigDecimal("11469.66"), composition.getSources().get(0).getCredits());
        assertEquals(new BigDecimal("0.9980"), composition.getSources().get(0).getShare());
    }

    @Test
    void realtimeCostRespectsRangeAndAgentWhileCompositionStaysAllAgents() {
        when(insightsDao.countTotalCredits(eq(1L), any(Date.class), eq(12L))).thenReturn(new BigDecimal("51.20"));
        when(insightsDao.countTotalCredits(eq(1L), any(Date.class), isNull())).thenReturn(new BigDecimal("82.61"));
        when(insightsDao.countCreditUsageWorkitems(eq(1L), any(Date.class), eq(12L))).thenReturn(10);
        when(insightsDao.listCreditsBySource(eq(1L), any(Date.class), isNull())).thenReturn(List.of(
                source("DISPATCH", "70"),
                source("IM", "12.61")
        ));

        RealtimeDashboardVO scoped = service.getRealtime(1L, 90, "12");
        RealtimeDashboardVO all = service.getRealtime(1L, 90, "all");

        CostKpiVO cost = scoped.getKpi().getCost();
        assertEquals(90, cost.getRange());
        assertEquals("12", cost.getAgentId());
        assertEquals(new BigDecimal("51.20"), cost.getTotal());
        assertEquals(new BigDecimal("5.12"), cost.getPerTask());
        assertEquals(new BigDecimal("0.57"), cost.getPerDay());
        assertEquals(new BigDecimal("82.61"), scoped.getCostComposition().getTotal());
        assertEquals(List.of("WORKITEM_EXECUTION", "IM"),
                scoped.getCostComposition().getSources().stream().map(CostCompositionVO.Source::getKey).toList());
        // 来源构成恒定全员口径：与同 range 的 agentId=all 成本 KPI 总数一致。
        assertEquals(all.getKpi().getCost().getTotal(), scoped.getCostComposition().getTotal());
        verify(insightsDao, times(2)).listCreditsBySource(eq(1L), any(Date.class), isNull());
    }

    @Test
    void realtimeCostUsesTheRequestedRangeForTheUsageWindow() {
        when(insightsDao.countTotalCredits(eq(1L), any(Date.class), isNull())).thenReturn(BigDecimal.ZERO);

        service.getRealtime(1L, 30, "all");

        ArgumentCaptor<Date> since = ArgumentCaptor.forClass(Date.class);
        verify(insightsDao).countTotalCredits(eq(1L), since.capture(), isNull());
        long hours = Duration.between(since.getValue().toInstant(), Instant.now()).toHours();
        assertTrue(hours >= 719 && hours <= 721, "range 30d usage window: " + hours + "h");
    }

    @Test
    void realtimeTreatsBlankAndAllAgentValuesAsAllAgents() {
        when(insightsDao.countTotalCredits(eq(1L), any(Date.class), isNull())).thenReturn(BigDecimal.ZERO);

        for (String agentId : Arrays.asList(null, "", "  ", "all", " all ")) {
            service.getRealtime(1L, 7, agentId);
        }

        verify(insightsDao, times(5)).countTotalCredits(eq(1L), any(Date.class), isNull());
        verify(insightsDao, times(5)).countCreditUsageWorkitems(eq(1L), any(Date.class), isNull());
    }

    @Test
    void realtimeRejectsUnsupportedRangeAndAgentBeforeTouchingTheDatabase() {
        BizException range = assertThrows(BizException.class, () -> service.getRealtime(1L, 14, null));
        assertEquals("dashboard.realtime_range_invalid", range.getMessageKey());
        assertEquals(14, range.getMessageArgs()[0]);

        BizException agent = assertThrows(BizException.class, () -> service.getRealtime(1L, 7, "abc"));
        assertEquals("dashboard.realtime_agent_invalid", agent.getMessageKey());
        assertEquals("abc", agent.getMessageArgs()[0]);

        assertThrows(BizException.class, () -> service.getRealtime(1L, 7, "0"));
        assertThrows(BizException.class, () -> service.getRealtime(1L, 7, "-3"));
        assertThrows(BizException.class, () -> service.getRealtime(1L, 7, "ALL"));
        verifyNoInteractions(dao, insightsDao);
    }

    @Test
    void realtimeCompositionIsEmptyWhenThereAreNoCredits() {
        when(insightsDao.countTotalCredits(anyLong(), any(Date.class), isNull())).thenReturn(null);

        RealtimeDashboardVO vo = service.getRealtime(1L, 7, "all");

        CostKpiVO cost = vo.getKpi().getCost();
        assertEquals(new BigDecimal("0.00"), cost.getTotal());
        assertEquals(new BigDecimal("0.00"), cost.getPerTask());
        assertEquals(new BigDecimal("0.00"), cost.getPerDay());
        assertEquals(new BigDecimal("0.00"), vo.getCostComposition().getTotal());
        assertTrue(vo.getCostComposition().getSources().isEmpty());
    }

    @Test
    void realtimeCompositionMergesSourcesSortsByCreditsAndKeepsTotalConsistent() {
        when(insightsDao.countTotalCredits(eq(1L), any(Date.class), isNull())).thenReturn(new BigDecimal("10.00"));
        when(insightsDao.listCreditsBySource(eq(1L), any(Date.class), isNull())).thenReturn(Arrays.asList(
                source("DISPATCH", "2.505"),
                source("DISPATCH", "2.500"),
                source("IM", "4.995"),
                source("BRAND_NEW", "1.00"),
                source("OTHER_CONVERSATION", "0"),
                source("OTHER_CONVERSATION", "-2"),
                null,
                source(null, "1"),
                source("PLATFORM_ASSISTANT", null)
        ));

        CostCompositionVO composition = service.getRealtime(1L, 7, "all").getCostComposition();

        assertEquals(2, composition.getSources().size());
        assertEquals(List.of("WORKITEM_EXECUTION", "IM"),
                composition.getSources().stream().map(CostCompositionVO.Source::getKey).toList());
        // 分位舍入差 0.01 由最大来源吸收，保持 total = sum(sources) 且份额合计 1。
        assertEquals(List.of(new BigDecimal("5.00"), new BigDecimal("5.00")),
                composition.getSources().stream().map(CostCompositionVO.Source::getCredits).toList());
        assertEquals(List.of(new BigDecimal("0.5000"), new BigDecimal("0.5000")),
                composition.getSources().stream().map(CostCompositionVO.Source::getShare).toList());
        BigDecimal summed = composition.getSources().stream()
                .map(CostCompositionVO.Source::getCredits)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, composition.getTotal().compareTo(summed));
    }

    private static InsightMetricsVO.CreditSource source(String source, String credits) {
        InsightMetricsVO.CreditSource row = new InsightMetricsVO.CreditSource();
        row.setSource(source);
        row.setCredits(credits == null ? null : new BigDecimal(credits));
        return row;
    }

    private static void assertWindow(DashboardService.PipelineWindow window,
                                     LocalDateTime expectedStart, LocalDateTime expectedEnd) {
        assertEquals(expectedStart, window.start());
        assertEquals(expectedEnd, window.end());
    }
}
