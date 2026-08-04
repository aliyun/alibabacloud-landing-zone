package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        service = new InsightsService(insightsDao, usageService);
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

}
