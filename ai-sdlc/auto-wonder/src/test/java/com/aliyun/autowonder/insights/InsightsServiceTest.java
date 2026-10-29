package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import com.aliyun.autowonder.insights.participation.CompletionFactQuery;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationProperties;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightsServiceTest {

    private InsightsDao insightsDao;
    private DispatchAiUsageService usageService;
    private InsightsService service;

    @BeforeEach
    void setUp() {
        insightsDao = mock(InsightsDao.class);
        usageService = mock(DispatchAiUsageService.class);
        service = new InsightsService(insightsDao, usageService,
                mock(HumanAgentParticipationRefreshService.class),
                new HumanAgentParticipationProperties(),
                mock(CompletionFactQuery.class));
    }

    @Test
    void getAuditDelegatesWorkerAndTimeRangeFilters() {
        InsightAuditItemVO item = new InsightAuditItemVO();
        item.setWorker("验收数字员工");
        item.setEventType("REJECT");
        when(insightsDao.listAuditItems(eq(1L), eq("medium"), eq(12L), eq("7d"), eq(50), eq(50)))
                .thenReturn(List.of(item));
        when(insightsDao.countAuditItems(eq(1L), eq("medium"), eq(12L), eq("7d")))
                .thenReturn(1);

        InsightAuditPageVO page = service.getAudit(1L, "medium", 12L, "7d", 2, 50);

        assertEquals(1, page.getTotal());
        assertEquals("验收数字员工", page.getItems().get(0).getWorker());
        verify(insightsDao).listAuditItems(1L, "medium", 12L, "7d", 50, 50);
        verify(insightsDao).countAuditItems(1L, "medium", 12L, "7d");
    }
    @Test
    void getMetricsUsesUsageWorkitemCountForAverageTokens() {
        when(insightsDao.countTotalTokens(eq(1L), any(), eq(12L))).thenReturn(900L);
        when(insightsDao.countWorkitems(eq(1L), any())).thenReturn(10);
        when(insightsDao.countUsageWorkitems(eq(1L), any(), eq(12L))).thenReturn(3);
        when(insightsDao.dailyTokenTrend(eq(1L), any(), eq(12L))).thenReturn(List.of());

        InsightMetricsVO metrics = service.getMetrics(1L, 12L, "30d");

        assertEquals(900L, metrics.getCost().getTotalTokens());
        assertEquals(300L, metrics.getCost().getAvgTokensPerTask());
    }

    @Test
    void getMetricsAggregatesCreditsWithCreditUsageWorkitemsAsAverageDenominator() {
        when(insightsDao.countTotalTokens(eq(1L), any(), eq(12L))).thenReturn(0L);
        when(insightsDao.countCreditUsageWorkitems(eq(1L), any(), eq(12L))).thenReturn(4);
        when(insightsDao.countTotalCredits(eq(1L), any(), eq(12L))).thenReturn(new BigDecimal("10.00"));
        when(insightsDao.dailyCreditsTrend(eq(1L), any(), eq(12L)))
                .thenReturn(List.of(Map.of("day", "2026-07-01", "credits", new BigDecimal("1.25"))));

        InsightMetricsVO metrics = service.getMetrics(1L, 12L, "30d");

        assertEquals(0, new BigDecimal("10.00").compareTo(metrics.getCost().getTotalCredits()));
        assertEquals(0, new BigDecimal("2.50").compareTo(metrics.getCost().getAvgCreditsPerTask()));
        assertEquals(0, new BigDecimal("0.33").compareTo(metrics.getCost().getDailyAvgCredits()));
        assertEquals(List.of(new BigDecimal("1.25")), metrics.getCost().getCreditsTrend());
        verify(insightsDao).countTotalCredits(eq(1L), any(Date.class), eq(12L));
        verify(insightsDao).countCreditUsageWorkitems(eq(1L), any(Date.class), eq(12L));
        verify(insightsDao).dailyCreditsTrend(eq(1L), any(Date.class), eq(12L));
    }

    @Test
    void getMetricsKeepsTokenAndCreditAverageDenominatorsSeparate() {
        when(insightsDao.countTotalTokens(eq(1L), any(), eq(12L))).thenReturn(1000L);
        when(insightsDao.countUsageWorkitems(eq(1L), any(), eq(12L))).thenReturn(2);
        when(insightsDao.countTotalCredits(eq(1L), any(), eq(12L))).thenReturn(new BigDecimal("10.00"));
        when(insightsDao.countCreditUsageWorkitems(eq(1L), any(), eq(12L))).thenReturn(5);

        InsightMetricsVO metrics = service.getMetrics(1L, 12L, "30d");

        assertEquals(500L, metrics.getCost().getAvgTokensPerTask());
        assertEquals(0, new BigDecimal("2.00").compareTo(metrics.getCost().getAvgCreditsPerTask()));
        verify(insightsDao).countUsageWorkitems(eq(1L), any(Date.class), eq(12L));
        verify(insightsDao).countCreditUsageWorkitems(eq(1L), any(Date.class), eq(12L));
    }

    @Test
    void getMetricsKeepsCreditsAtZeroWhenNoUsageDataExists() {
        InsightMetricsVO metrics = service.getMetrics(1L, null, "7d");

        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.getCost().getTotalCredits()));
        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.getCost().getAvgCreditsPerTask()));
        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.getCost().getDailyAvgCredits()));
        assertEquals(7, metrics.getCost().getCreditsTrend().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.getCost().getCreditsTrend().get(0)));
    }

    @Test
    void getMetricsTreatsMissingAndNumericTrendCreditsAsBigDecimal() {
        when(insightsDao.dailyCreditsTrend(eq(1L), any(), isNull()))
                .thenReturn(List.of(Map.of("day", "2026-07-01"), Map.of("day", "2026-07-02", "credits", 3)));

        InsightMetricsVO metrics = service.getMetrics(1L, null, "30d");

        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.getCost().getCreditsTrend().get(0)));
        assertEquals(0, new BigDecimal("3").compareTo(metrics.getCost().getCreditsTrend().get(1)));
    }

    @Test
    void getMetricsReturnsSevenZeroCreditsWhenTrendWindowHasNoRows() {
        when(insightsDao.dailyCreditsTrend(eq(1L), any(), isNull())).thenReturn(List.of());

        InsightMetricsVO metrics = service.getMetrics(1L, null, "30d");

        assertEquals(7, metrics.getCost().getCreditsTrend().size());
        for (BigDecimal value : metrics.getCost().getCreditsTrend()) {
            assertEquals(0, BigDecimal.ZERO.compareTo(value));
        }
    }

    @Test
    void getMetricsExposesWorkerAndSourceBreakdownsWithFixedOrdering() {
        when(insightsDao.listWorkerCredits(eq(1L), any(), eq(12L)))
                .thenReturn(List.of(workerCost(100L, "DEV", "5.75", "1.75"),
                        workerCost(101L, "CR", "0", "0"),
                        workerCost(102L, null, null, null)));
        when(insightsDao.listCreditsBySource(eq(1L), any(), eq(12L)))
                .thenReturn(Arrays.asList(source("IM", "7"), source("DISPATCH", "4"),
                        source("WORKITEM_CLARIFICATION", "0"),
                        source("OTHER_CONVERSATION", "11.5"),
                        source("PLATFORM_ASSISTANT", "2"), source("IM", "2"),
                        source("UNKNOWN_SOURCE", "5"),
                        null, new InsightMetricsVO.CreditSource()));

        InsightMetricsVO metrics = service.getMetrics(1L, 12L, "30d");

        List<InsightMetricsVO.WorkerCost> byWorker = metrics.getCost().getByWorker();
        assertEquals(1, byWorker.size());
        assertEquals(100L, byWorker.get(0).getAgentId());
        assertEquals("DEV", byWorker.get(0).getAgentName());
        assertEquals(0, new BigDecimal("5.75").compareTo(byWorker.get(0).getCredits()));
        assertEquals(0, new BigDecimal("1.75").compareTo(byWorker.get(0).getConversationCredits()));

        List<InsightMetricsVO.CreditSource> bySource = metrics.getCost().getBySource();
        assertEquals(List.of("DISPATCH", "PLATFORM_ASSISTANT", "IM", "OTHER_CONVERSATION"),
                bySource.stream().map(InsightMetricsVO.CreditSource::getSource).collect(Collectors.toList()));
        assertEquals(0, new BigDecimal("4").compareTo(bySource.get(0).getCredits()));
        assertEquals(0, new BigDecimal("9").compareTo(bySource.get(2).getCredits()));
        verify(insightsDao).listWorkerCredits(eq(1L), any(Date.class), eq(12L));
        verify(insightsDao).listCreditsBySource(eq(1L), any(Date.class), eq(12L));
    }

    @Test
    void getMetricsReturnsEmptyBreakdownsWhenDaoListsAreNull() {
        when(insightsDao.listWorkerCredits(eq(1L), any(), isNull())).thenReturn(null);
        when(insightsDao.listCreditsBySource(eq(1L), any(), isNull())).thenReturn(null);

        InsightMetricsVO metrics = service.getMetrics(1L, null, "30d");

        assertEquals(List.of(), metrics.getCost().getByWorker());
        assertEquals(List.of(), metrics.getCost().getBySource());
    }

    private static InsightMetricsVO.WorkerCost workerCost(Long agentId, String agentName,
                                                          String credits, String conversationCredits) {
        InsightMetricsVO.WorkerCost row = new InsightMetricsVO.WorkerCost();
        row.setAgentId(agentId);
        row.setAgentName(agentName);
        row.setCredits(credits == null ? null : new BigDecimal(credits));
        row.setConversationCredits(conversationCredits == null ? null : new BigDecimal(conversationCredits));
        return row;
    }

    private static InsightMetricsVO.CreditSource source(String source, String credits) {
        InsightMetricsVO.CreditSource row = new InsightMetricsVO.CreditSource();
        row.setSource(source);
        row.setCredits(new BigDecimal(credits));
        return row;
    }

}
