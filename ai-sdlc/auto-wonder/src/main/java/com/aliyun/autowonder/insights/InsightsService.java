package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.aiusage.dto.DispatchAiUsageBackfillResult;
import com.aliyun.autowonder.insights.dto.HumanAgentParticipationVO;
import com.aliyun.autowonder.insights.dto.HumanAgentSlowTailPageVO;
import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator.Granularity;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator.ParticipationSummary;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator.TrendBucket;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationFact;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationProperties;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRefreshService;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InsightsService {

    private final InsightsDao insightsDao;
    private final DispatchAiUsageService usageService;
    private final HumanAgentParticipationRefreshService participationRefreshService;
    private final HumanAgentParticipationProperties participationProperties;
    private final HumanAgentParticipationCalculator participationCalculator;

    public InsightsService(InsightsDao insightsDao,
                            DispatchAiUsageService usageService,
                            HumanAgentParticipationRefreshService participationRefreshService,
                            HumanAgentParticipationProperties participationProperties) {
        this.insightsDao = insightsDao;
        this.usageService = usageService;
        this.participationRefreshService = participationRefreshService;
        this.participationProperties = participationProperties;
        this.participationCalculator = new HumanAgentParticipationCalculator();
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

    public boolean forceParticipationRefresh(long tenantId) {
        return participationRefreshService.forceRefresh(tenantId);
    }

    public HumanAgentParticipationVO getParticipation(long tenantId, LocalDate startDate,
                                                       LocalDate endDate, String granularity) {
        Optional<ParsedSnapshot> snapshotOpt = participationRefreshService.read(tenantId);
        if (snapshotOpt.isEmpty()) {
            participationRefreshService.requestRefresh(tenantId);
            participationRefreshService.waitForRefresh(tenantId, participationProperties.getCacheMissWaitMs());
            snapshotOpt = participationRefreshService.read(tenantId);
            if (snapshotOpt.isEmpty()) {
                HumanAgentParticipationVO vo = new HumanAgentParticipationVO();
                vo.setAvailable(false);
                vo.setRefreshTriggered(true);
                return vo;
            }
        }
        return buildParticipationResponse(snapshotOpt.get(), startDate, endDate, granularity);
    }

    private HumanAgentParticipationVO buildParticipationResponse(ParsedSnapshot snapshot,
                                                                   LocalDate startDate, LocalDate endDate,
                                                                   String granularity) {
        HumanAgentParticipationVO vo = new HumanAgentParticipationVO();
        LocalDate dataThrough = LocalDate.parse(snapshot.dataThrough());
        if (startDate.isAfter(endDate) || endDate.isAfter(dataThrough)) {
            throw new IllegalArgumentException("Invalid date range: start_date must be <= end_date and end_date <= dataThrough");
        }

        Granularity g = Granularity.valueOf(granularity.toUpperCase());
        ZoneId zone = ZoneId.of(participationProperties.getTimezone());
        ParticipationSummary summary = participationCalculator.summarize(
                snapshot.items(), startDate, endDate, g, zone);

        vo.setAvailable(true);
        vo.setGeneratedAt(snapshot.generatedAt());
        vo.setDataThrough(snapshot.dataThrough());
        vo.setRefreshTriggered(false);
        vo.setSampleSize(summary.eligibleFacts().size());

        HumanAgentParticipationVO.DurationSummary avg = new HumanAgentParticipationVO.DurationSummary(
                summary.averageTotalSeconds(), summary.averageHumanSeconds(), summary.averageAgentSeconds());
        vo.setAverage(avg);

        if (summary.p90() != null) {
            vo.setP90(toP90Workitem(summary.p90()));
        }

        List<HumanAgentParticipationVO.TrendEntry> trendEntries = new ArrayList<>();
        for (TrendBucket tb : summary.trend()) {
            HumanAgentParticipationVO.TrendEntry te = new HumanAgentParticipationVO.TrendEntry();
            te.setLabel(tb.label());
            te.setAverageTotalSeconds(tb.averageTotalSeconds());
            te.setAverageHumanSeconds(tb.averageHumanSeconds());
            te.setAverageAgentSeconds(tb.averageAgentSeconds());
            trendEntries.add(te);
        }
        vo.setTrend(trendEntries);
        return vo;
    }

    public HumanAgentSlowTailPageVO getSlowTail(long tenantId, LocalDate startDate,
                                                  LocalDate endDate, int page, int pageSize) {
        Optional<ParsedSnapshot> snapshotOpt = participationRefreshService.read(tenantId);
        if (snapshotOpt.isEmpty()) {
            participationRefreshService.requestRefresh(tenantId);
            participationRefreshService.waitForRefresh(tenantId, participationProperties.getCacheMissWaitMs());
            snapshotOpt = participationRefreshService.read(tenantId);
            if (snapshotOpt.isEmpty()) {
                HumanAgentSlowTailPageVO vo = new HumanAgentSlowTailPageVO();
                vo.setTailSize(0);
                vo.setTotal(0);
                vo.setPage(page);
                vo.setPageSize(pageSize);
                vo.setItems(Collections.emptyList());
                return vo;
            }
        }
        return buildSlowTailResponse(snapshotOpt.get(), startDate, endDate, page, pageSize);
    }

    private HumanAgentSlowTailPageVO buildSlowTailResponse(ParsedSnapshot snapshot,
                                                             LocalDate startDate, LocalDate endDate,
                                                             int page, int pageSize) {
        HumanAgentSlowTailPageVO vo = new HumanAgentSlowTailPageVO();
        LocalDate dataThrough = LocalDate.parse(snapshot.dataThrough());
        if (startDate.isAfter(endDate) || endDate.isAfter(dataThrough)) {
            throw new IllegalArgumentException("Invalid date range: start_date must be <= end_date and end_date <= dataThrough");
        }

        Granularity g = Granularity.DAY;
        ZoneId zone = ZoneId.of(participationProperties.getTimezone());
        ParticipationSummary summary = participationCalculator.summarize(
                snapshot.items(), startDate, endDate, g, zone);

        List<HumanAgentParticipationFact> tail = summary.slowTail();
        int total = tail.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<HumanAgentParticipationVO.P90Workitem> items = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            items.add(toP90Workitem(tail.get(i)));
        }
        vo.setTailSize(total);
        vo.setTotal(total);
        vo.setPage(page);
        vo.setPageSize(pageSize);
        vo.setItems(items);
        return vo;
    }

    private HumanAgentParticipationVO.P90Workitem toP90Workitem(HumanAgentParticipationFact f) {
        HumanAgentParticipationVO.P90Workitem p = new HumanAgentParticipationVO.P90Workitem();
        p.setWorkitemId(f.workitemId());
        p.setTitle(f.title());
        p.setCompletedAt(f.completedAt().toString());
        p.setTotalDurationSeconds(f.totalDurationSeconds());
        p.setHumanDurationSeconds(f.humanDurationSeconds());
        p.setAgentDurationSeconds(f.agentDurationSeconds());
        return p;
    }
}
