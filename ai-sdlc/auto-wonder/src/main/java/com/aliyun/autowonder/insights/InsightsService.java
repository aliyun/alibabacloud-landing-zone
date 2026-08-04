package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.aiusage.dto.DispatchAiUsageBackfillResult;
import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InsightsService {

    private final InsightsDao insightsDao;
    private final DispatchAiUsageService usageService;

    public InsightsService(InsightsDao insightsDao, DispatchAiUsageService usageService) {
        this.insightsDao = insightsDao;
        this.usageService = usageService;
    }

    public InsightMetricsVO getMetrics(long tenantId, Long agentId, String timeRange) {
        Date since = computeSince(timeRange);
        InsightMetricsVO vo = new InsightMetricsVO();

        // Cost
        InsightMetricsVO.CostMetrics cost = new InsightMetricsVO.CostMetrics();
        long totalTokens = insightsDao.countTotalTokens(tenantId, since, agentId);
        int totalTasks = insightsDao.countWorkitems(tenantId, since);
        int usageWorkitems = insightsDao.countUsageWorkitems(tenantId, since, agentId);
        cost.setTotalTokens(totalTokens);
        cost.setAvgTokensPerTask(usageWorkitems > 0 ? totalTokens / usageWorkitems : 0);
        int days = daysFromRange(timeRange);
        cost.setDailyAvg(days > 0 ? totalTokens / days : 0);
        cost.setTrend(buildTokenTrend(tenantId, since, agentId));
        vo.setCost(cost);

        // Efficiency
        InsightMetricsVO.EfficiencyMetrics eff = new InsightMetricsVO.EfficiencyMetrics();
        int completedTasks = insightsDao.countCompletedWorkitems(tenantId, since);
        eff.setTotalTasks(totalTasks);
        eff.setCompletedTasks(completedTasks);
        eff.setCompletionRate(totalTasks > 0 ? round1((double) completedTasks / totalTasks * 100.0) : 0);
        Integer avgDur = insightsDao.avgDispatchDurationMinutes(tenantId, since, agentId);
        eff.setAvgDurationMinutes(avgDur != null ? avgDur : 0);
        eff.setTrend(Collections.nCopies(7, (int) Math.round(eff.getCompletionRate())));
        vo.setEfficiency(eff);

        // Stability
        InsightMetricsVO.StabilityMetrics stab = new InsightMetricsVO.StabilityMetrics();
        int firstPass = insightsDao.countFirstPassDispatches(tenantId, since, agentId);
        int totalDispatches = insightsDao.countTotalDispatches(tenantId, since, agentId);
        stab.setSuccessRate(totalDispatches > 0 ? round1((double) firstPass / totalDispatches * 100.0) : 100);
        stab.setRetryCount(insightsDao.countRetryDispatches(tenantId, since, agentId));
        stab.setBlockedCount(insightsDao.countBlockedDispatches(tenantId, since, agentId));
        stab.setTrend(Collections.nCopies(7, (int) Math.round(stab.getSuccessRate())));
        vo.setStability(stab);

        // Security
        InsightMetricsVO.SecurityMetrics sec = new InsightMetricsVO.SecurityMetrics();
        int highRisk = insightsDao.countHighRiskAuditLogs(tenantId, since);
        int totalAudit = insightsDao.countTotalAuditLogs(tenantId, since);
        sec.setHighRiskOps(highRisk);
        sec.setComplianceRate(totalAudit > 0 ? round1((double) (totalAudit - highRisk) / totalAudit * 100.0) : 100);
        sec.setAuditBlocks(insightsDao.countAuditBlocks(tenantId, since));
        sec.setTrend(Collections.nCopies(7, highRisk));
        vo.setSecurity(sec);

        return vo;
    }

    public InsightAuditPageVO getAudit(long tenantId, String riskLevel, Long workerId, String timeRange, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<InsightAuditItemVO> items = insightsDao.listAuditItems(tenantId, riskLevel, workerId, timeRange, offset, pageSize);
        int total = insightsDao.countAuditItems(tenantId, riskLevel, workerId, timeRange);
        return new InsightAuditPageVO(items, total);
    }

    public List<InsightWorkerVO> getWorkers(long tenantId) {
        return insightsDao.listActiveWorkers(tenantId);
    }

    public DispatchAiUsageBackfillResult backfillUsage(long tenantId) {
        return usageService.backfillUsageArtifacts(tenantId);
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private Date computeSince(String timeRange) {
        Calendar cal = Calendar.getInstance();
        switch (timeRange) {
            case "7d":
                cal.add(Calendar.DAY_OF_MONTH, -7);
                break;
            case "90d":
                cal.add(Calendar.DAY_OF_MONTH, -90);
                break;
            default:
                cal.add(Calendar.DAY_OF_MONTH, -30);
                break;
        }
        return cal.getTime();
    }

    private int daysFromRange(String timeRange) {
        switch (timeRange) {
            case "7d":
                return 7;
            case "90d":
                return 90;
            default:
                return 30;
        }
    }

    private List<Integer> buildTokenTrend(long tenantId, Date since, Long agentId) {
        List<Map<String, Object>> raw = insightsDao.dailyTokenTrend(tenantId, since, agentId);
        if (raw == null || raw.isEmpty()) {
            return Collections.nCopies(7, 0);
        }
        return raw.stream()
                .map(m -> ((Number) m.getOrDefault("tokens", 0)).intValue())
                .collect(Collectors.toList());
    }
}
