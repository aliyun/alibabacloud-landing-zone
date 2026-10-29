package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;

import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.aiusage.dto.DispatchAiUsageBackfillResult;
import com.aliyun.autowonder.insights.dto.HumanAgentParticipationVO;
import com.aliyun.autowonder.insights.dto.HumanAgentSlowTailVO;
import com.aliyun.autowonder.insights.dto.InsightAuditItemVO;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import com.aliyun.autowonder.insights.participation.CompletionFactQuery;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator.ParticipationSummary;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationCalculator.TrendBucket;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationFact;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationProperties;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationRefreshService;
import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import com.aliyun.autowonder.insights.participation.ParticipationRefreshResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InsightsService {

    private static final List<String> CREDIT_SOURCE_ORDER = List.of(
            "DISPATCH", "WORKITEM_CLARIFICATION", "PLATFORM_ASSISTANT", "IM", "OTHER_CONVERSATION");
    static final int MAX_PARTICIPATION_RANGE_DAYS = 180;

    private final InsightsDao insightsDao;
    private final DispatchAiUsageService usageService;
    private final HumanAgentParticipationRefreshService participationRefreshService;
    private final HumanAgentParticipationProperties participationProperties;
    private final HumanAgentParticipationCalculator participationCalculator;
    private final CompletionFactQuery completionFactQuery;

    public InsightsService(InsightsDao insightsDao,
                            DispatchAiUsageService usageService,
                            HumanAgentParticipationRefreshService participationRefreshService,
                            HumanAgentParticipationProperties participationProperties,
                            CompletionFactQuery completionFactQuery) {
        this.insightsDao = insightsDao;
        this.usageService = usageService;
        this.participationRefreshService = participationRefreshService;
        this.participationProperties = participationProperties;
        this.participationCalculator = new HumanAgentParticipationCalculator();
        this.completionFactQuery = completionFactQuery;
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
        BigDecimal totalCredits = insightsDao.countTotalCredits(tenantId, since, agentId);
        if (totalCredits == null) {
            totalCredits = BigDecimal.ZERO;
        }
        cost.setTotalCredits(totalCredits);
        // Credits 均值分母包含「只有工单绑定对话用量」的工单；Token 指标口径保持不变。
        int creditUsageWorkitems = insightsDao.countCreditUsageWorkitems(tenantId, since, agentId);
        cost.setAvgCreditsPerTask(creditUsageWorkitems > 0
                ? totalCredits.divide(BigDecimal.valueOf(creditUsageWorkitems), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        cost.setDailyAvgCredits(days > 0
                ? totalCredits.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        cost.setCreditsTrend(buildCreditsTrend(tenantId, since, agentId));
        cost.setByWorker(buildWorkerCosts(insightsDao.listWorkerCredits(tenantId, since, agentId)));
        cost.setBySource(buildCreditSources(insightsDao.listCreditsBySource(tenantId, since, agentId)));
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

    private List<BigDecimal> buildCreditsTrend(long tenantId, Date since, Long agentId) {
        List<Map<String, Object>> raw = insightsDao.dailyCreditsTrend(tenantId, since, agentId);
        if (raw == null || raw.isEmpty()) {
            return Collections.nCopies(7, BigDecimal.ZERO);
        }
        return raw.stream()
                .map(m -> toBigDecimal(m.get("credits")))
                .collect(Collectors.toList());
    }

    private List<InsightMetricsVO.WorkerCost> buildWorkerCosts(List<InsightMetricsVO.WorkerCost> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getCredits() != null
                        && row.getCredits().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
    }

    private List<InsightMetricsVO.CreditSource> buildCreditSources(List<InsightMetricsVO.CreditSource> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, BigDecimal> creditsBySource = new HashMap<>();
        for (InsightMetricsVO.CreditSource row : rows) {
            if (row == null || row.getSource() == null || row.getCredits() == null) {
                continue;
            }
            creditsBySource.merge(row.getSource(), row.getCredits(), BigDecimal::add);
        }
        List<InsightMetricsVO.CreditSource> ordered = new ArrayList<>();
        for (String source : CREDIT_SOURCE_ORDER) {
            BigDecimal credits = creditsBySource.get(source);
            if (credits != null && credits.compareTo(BigDecimal.ZERO) > 0) {
                InsightMetricsVO.CreditSource item = new InsightMetricsVO.CreditSource();
                item.setSource(source);
                item.setCredits(credits);
                ordered.add(item);
            }
        }
        return ordered;
    }

    private BigDecimal toBigDecimal(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        return raw instanceof BigDecimal ? (BigDecimal) raw : new BigDecimal(String.valueOf(raw));
    }

    public ParticipationRefreshResult forceParticipationRefresh(long tenantId) {
        return participationRefreshService.forceRefresh(tenantId);
    }

    public HumanAgentParticipationVO getParticipation(long tenantId, LocalDate startDate,
                                                       LocalDate endDate) {
        return getParticipation(tenantId, startDate, endDate, null);
    }

    public HumanAgentParticipationVO getParticipation(long tenantId, LocalDate startDate,
                                                       LocalDate endDate, String workType) {
        Optional<ParsedSnapshot> snapshotOpt = completionFactQuery.ensureSnapshot(tenantId);
        if (snapshotOpt.isEmpty()) {
            HumanAgentParticipationVO vo = new HumanAgentParticipationVO();
            vo.setAvailable(false);
            vo.setRefreshTriggered(true);
            return vo;
        }
        return buildParticipationResponse(snapshotOpt, tenantId, startDate, endDate, workType);
    }

    private HumanAgentParticipationVO buildParticipationResponse(Optional<ParsedSnapshot> snapshotOpt,
                                                                   long tenantId,
                                                                   LocalDate startDate, LocalDate endDate,
                                                                   String workType) {
        ParsedSnapshot snapshot = snapshotOpt.get();
        ZoneId zone = ZoneId.of(participationProperties.getTimezone());
        LocalDate today = LocalDate.now(zone);
        validateParticipationRange(startDate, endDate, today);

        HumanAgentParticipationVO vo = new HumanAgentParticipationVO();
        List<HumanAgentParticipationFact> facts = completionFactQuery.range(
                snapshotOpt, tenantId, startDate, endDate, workType);
        boolean realtimeTail = endDate.isEqual(today) && completionFactQuery.includesRealtimeTail(endDate);
        ParticipationSummary summary = participationCalculator.summarize(
                facts, startDate, endDate, zone);

        vo.setAvailable(true);
        vo.setGeneratedAt(realtimeTail ? Instant.now().toString() : snapshot.generatedAt());
        vo.setDataThrough(realtimeTail ? today.toString() : snapshot.dataThrough());
        vo.setRealtimeTail(realtimeTail);
        vo.setRefreshTriggered(false);
        vo.setSampleSize(summary.eligibleFacts().size());
        vo.setAverageTotalMinutes(HumanAgentParticipationCalculator.averageMinutes(summary.eligibleFacts()));

        HumanAgentParticipationVO.DurationSummary avg = new HumanAgentParticipationVO.DurationSummary(
                summary.averageTotalSeconds(), summary.averageHumanSeconds(), summary.averageAgentSeconds());
        avg.setHumanShare(humanShare(summary.totalHumanSeconds(), summary.totalAgentSeconds()));
        vo.setAverage(avg);
        vo.setMedianTotalSeconds(summary.medianTotalSeconds());
        vo.setAgentWorkitemCount(summary.agentWorkitemCount());
        vo.setInferredAssignmentCount((int) summary.eligibleFacts().stream()
                .filter(HumanAgentParticipationFact::inferredAssignment).count());
        java.util.Map<String, Integer> exclusions = new java.util.LinkedHashMap<>();
        for (HumanAgentParticipationFact fact : facts) {
            if (fact.exclusionReason() != null) {
                exclusions.merge(fact.exclusionReason(), 1, Integer::sum);
            }
        }
        vo.setExclusions(exclusions);
        vo.setIdentifiedCompletedCount(vo.getSampleSize() + exclusions.values().stream().mapToInt(Integer::intValue).sum());

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
            te.setSampleSize(tb.sampleSize());
            te.setHumanShare(humanShare(tb.totalHumanSeconds(), tb.totalAgentSeconds()));
            trendEntries.add(te);
        }
        vo.setTrend(trendEntries);
        return vo;
    }

    private static Double humanShare(long human, long agent) {
        return human + agent == 0 ? null : (double) human / (human + agent);
    }

    public HumanAgentSlowTailVO getSlowTail(long tenantId, LocalDate startDate, LocalDate endDate) {
        return getSlowTail(tenantId, startDate, endDate, null);
    }

    public HumanAgentSlowTailVO getSlowTail(long tenantId, LocalDate startDate, LocalDate endDate,
                                            String workType) {
        Optional<ParsedSnapshot> snapshotOpt = completionFactQuery.ensureSnapshot(tenantId);
        if (snapshotOpt.isEmpty()) {
            HumanAgentSlowTailVO vo = new HumanAgentSlowTailVO();
            vo.setItems(Collections.emptyList());
            return vo;
        }
        return buildSlowTailResponse(snapshotOpt, tenantId, startDate, endDate, workType);
    }

    private HumanAgentSlowTailVO buildSlowTailResponse(Optional<ParsedSnapshot> snapshotOpt,
                                                             long tenantId,
                                                             LocalDate startDate, LocalDate endDate,
                                                             String workType) {
        HumanAgentSlowTailVO vo = new HumanAgentSlowTailVO();
        ZoneId zone = ZoneId.of(participationProperties.getTimezone());
        LocalDate today = LocalDate.now(zone);
        validateParticipationRange(startDate, endDate, today);

        List<HumanAgentParticipationFact> facts = completionFactQuery.range(
                snapshotOpt, tenantId, startDate, endDate, workType);

        List<HumanAgentParticipationVO.P90Workitem> items = new ArrayList<>();
        for (HumanAgentParticipationFact fact
                : HumanAgentParticipationCalculator.slowTail(facts, startDate, endDate, zone)) {
            items.add(toP90Workitem(fact));
        }
        vo.setItems(items);
        return vo;
    }

    private static void validateParticipationRange(LocalDate startDate, LocalDate endDate, LocalDate today) {
        boolean tooWide = !startDate.isAfter(endDate)
                && ChronoUnit.DAYS.between(startDate, endDate) > MAX_PARTICIPATION_RANGE_DAYS;
        if (startDate.isAfter(endDate) || endDate.isAfter(today) || tooWide) {
            throw new KeyedIllegalArgumentException("insights.invalid_date_range", null,
                    "Invalid date range: start_date must be <= end_date and end_date <= today");
        }
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
