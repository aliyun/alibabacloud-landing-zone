package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.insights.InsightsDao;
import com.aliyun.autowonder.insights.InsightsService;
import com.aliyun.autowonder.insights.dto.HumanAgentParticipationVO;
import com.aliyun.autowonder.insights.participation.CompletionFactQuery;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationFact;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationProperties;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRefreshService;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Design §7 跨端点契约：同窗下人机协作平均完成时长与流水线健康平均耗时必须逐字符一致，
 * 包括「今日」纯实时尾与今日 00:00 的快照/尾边界。
 */
class PipelineParticipationCaliberContractTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long TENANT = 1L;

    @Test
    void participationAndPipelineHealthAgreeOnEveryWindowIncludingTheRealtimeTail() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);

        DashboardDao dashboardDao = mock(DashboardDao.class);
        when(dashboardDao.pipelineExecutionStats(anyLong(), any(), any())).thenReturn(Map.of());
        when(dashboardDao.pipelineReworkStats(anyLong(), any(), any())).thenReturn(Map.of());

        HumanAgentParticipationRefreshService refreshService = mock(HumanAgentParticipationRefreshService.class);
        HumanAgentParticipationProperties properties = new HumanAgentParticipationProperties();
        CompletionFactQuery factQuery = new CompletionFactQuery(refreshService, properties);
        DashboardService dashboardService = new DashboardService(
                dashboardDao, mock(InsightsDao.class), factQuery, "FACT");
        InsightsService insightsService = new InsightsService(
                mock(InsightsDao.class), mock(DispatchAiUsageService.class),
                refreshService, properties, factQuery);

        List<HumanAgentParticipationFact> snapshotFacts = List.of(
                fact(1, yesterday.atTime(12, 0).atZone(ZONE).toInstant(), 3600),
                fact(2, today.minusDays(7).atTime(12, 0).atZone(ZONE).toInstant(), 7200),
                fact(3, today.minusDays(30).atTime(12, 0).atZone(ZONE).toInstant(), 5400),
                fact(4, today.minusDays(90).atTime(12, 0).atZone(ZONE).toInstant(), 3600),
                fact(5, today.minusDays(200).atTime(12, 0).atZone(ZONE).toInstant(), 1000));
        when(refreshService.read(TENANT)).thenReturn(Optional.of(new ParsedSnapshot(
                today.atTime(3, 0).atZone(ZONE).toInstant().toString(), yesterday.toString(), snapshotFacts)));
        when(refreshService.todayFacts(TENANT)).thenReturn(List.of(
                fact(6, today.atStartOfDay(ZONE).toInstant(), 1800),
                fact(7, today.atTime(1, 0).atZone(ZONE).toInstant(), 2700)));

        Map<String, Integer> expected = Map.of("today", 38, "7d", 90, "30d", 90, "90d", 83);
        for (String range : List.of("today", "7d", "30d", "90d")) {
            DashboardService.PipelineWindow window = DashboardService.pipelineWindow(range, today);

            Integer pipelineAverage = dashboardService.getPipelineHealth(TENANT, range)
                    .getAvgWorkitemDurationMinutes();
            HumanAgentParticipationVO participation = insightsService.getParticipation(
                    TENANT, window.startDate(), window.endDateInclusive());

            assertEquals(expected.get(range), pipelineAverage, range + " 的流水线健康平均值");
            assertEquals(pipelineAverage, participation.getAverageTotalMinutes(),
                    range + " 同窗下两端平均值必须完全一致");
        }

        HumanAgentParticipationVO todayParticipation = insightsService.getParticipation(TENANT, today, today);
        assertTrue(todayParticipation.isRealtimeTail(), "今日窗口必须包含实时尾");
        assertEquals(today.toString(), todayParticipation.getDataThrough());
        assertEquals(2, todayParticipation.getSampleSize(),
                "今日样本应包含 00:00 边界工单与当日晚些时候完成的工单");

        HumanAgentParticipationVO weekParticipation = insightsService.getParticipation(
                TENANT, today.minusDays(7), yesterday);
        assertFalse(weekParticipation.isRealtimeTail());
        assertEquals(yesterday.toString(), weekParticipation.getDataThrough());
    }

    private static HumanAgentParticipationFact fact(long id, Instant completedAt, long seconds) {
        return new HumanAgentParticipationFact(id, "w" + id, completedAt, seconds, seconds / 2, seconds / 2);
    }
}
