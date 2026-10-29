package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dashboard.dto.CompletedWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.CostCompositionVO;
import com.aliyun.autowonder.dashboard.dto.CostKpiVO;
import com.aliyun.autowonder.dashboard.dto.HealthVO;
import com.aliyun.autowonder.dashboard.dto.InventoryVO;
import com.aliyun.autowonder.dashboard.dto.KpiVO;
import com.aliyun.autowonder.dashboard.dto.OverviewKpiVO;
import com.aliyun.autowonder.dashboard.dto.OverviewVO;
import com.aliyun.autowonder.dashboard.dto.PipelineHealthVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.ReworkVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.dashboard.dto.RunningWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.SquadLineVO;
import com.aliyun.autowonder.dashboard.dto.SuccessSubVO;
import com.aliyun.autowonder.dashboard.dto.WorkstationVO;
import com.aliyun.autowonder.insights.InsightsDao;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.participation.CompletionFactQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DashboardService {

    private static final int FEED_LIMIT = 10;
    private static final int DEFAULT_REALTIME_RANGE = 7;
    private static final Set<Integer> REALTIME_RANGES = Set.of(7, 30, 90);
    private static final String AGENT_ALL = "all";
    private static final String SCOPE_ALL_AGENTS = "ALL_AGENTS";
    private static final ZoneId DASHBOARD_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PIPELINE_RANGES = Set.of("today", "7d", "30d", "90d");
    // 与 InsightsService 的来源顺序一致；key/name 为 /realtime 契约值，source 为 credits 聚合查询的枚举值。
    private static final List<CreditSourceMeta> CREDIT_SOURCE_META = List.of(
            new CreditSourceMeta("DISPATCH", "WORKITEM_EXECUTION", "工单执行"),
            new CreditSourceMeta("WORKITEM_CLARIFICATION", "REQUIREMENT_CLARIFY", "需求澄清"),
            new CreditSourceMeta("PLATFORM_ASSISTANT", "CHIEF_OF_STAFF", "平台管家（Chief Of Staff）"),
            new CreditSourceMeta("IM", "IM", "IM（钉钉 / 飞书）"),
            new CreditSourceMeta("OTHER_CONVERSATION", "OTHER_CONVERSATION", "其他对话（记忆整理、流程优化分析等）"));

    private record CreditSourceMeta(String source, String key, String name) {
    }

    private final DashboardDao dao;
    private final InsightsDao insightsDao;
    private final CompletionFactQuery completionFactQuery;
    private final String avgSource;

    public DashboardService(DashboardDao dao, InsightsDao insightsDao,
                            CompletionFactQuery completionFactQuery,
                            @Value("${dashboard.pipeline-health.avg-source:FACT}") String avgSource) {
        this.dao = dao;
        this.insightsDao = insightsDao;
        this.completionFactQuery = completionFactQuery;
        this.avgSource = avgSource;
    }

    public RealtimeDashboardVO getRealtime(long tenantId) {
        return getRealtime(tenantId, null, null);
    }

    public RealtimeDashboardVO getRealtime(long tenantId, Integer range, String agentId) {
        int effectiveRange = normalizeRealtimeRange(range);
        Long effectiveAgentId = normalizeAgentId(agentId);
        Date since = costSince(effectiveRange);
        BigDecimal scopedTotal = scale2(insightsDao.countTotalCredits(tenantId, since, effectiveAgentId));
        // 来源构成恒定按全员聚合；与 kpi.cost(agentId=all) 复用同一个总数，保证两块数字一致。
        BigDecimal allAgentsTotal = effectiveAgentId == null
                ? scopedTotal
                : scale2(insightsDao.countTotalCredits(tenantId, since, null));
        int creditUsageWorkitems = insightsDao.countCreditUsageWorkitems(tenantId, since, effectiveAgentId);

        RealtimeDashboardVO vo = new RealtimeDashboardVO();
        KpiVO kpi = buildKpi(tenantId);
        kpi.setCost(buildCostKpi(effectiveRange, effectiveAgentId, scopedTotal, creditUsageWorkitems));
        vo.setKpi(kpi);
        vo.setCostComposition(buildCostComposition(effectiveRange, allAgentsTotal,
                insightsDao.listCreditsBySource(tenantId, since, null)));
        vo.setInventory(buildInventory(tenantId));
        vo.setSquads(buildSquads(tenantId));
        vo.setWorkstations(buildWorkstations(tenantId));
        vo.setHealth(buildHealth(tenantId));
        vo.setRunningFeed(dao.listRunningFeed(tenantId, FEED_LIMIT));
        vo.setRecentFeed(dao.listRecentFeed(tenantId, FEED_LIMIT));
        vo.setGeneratedAt(Instant.now().toString());
        return vo;
    }

    private int normalizeRealtimeRange(Integer range) {
        if (range == null) {
            return DEFAULT_REALTIME_RANGE;
        }
        if (!REALTIME_RANGES.contains(range)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "dashboard.realtime_range_invalid",
                    new Object[]{range}, "unsupported dashboard realtime range: " + range);
        }
        return range;
    }

    private Long normalizeAgentId(String agentId) {
        if (agentId == null || agentId.isBlank() || AGENT_ALL.equals(agentId.trim())) {
            return null;
        }
        String value = agentId.trim();
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 统一落到下方的 keyed 参数错误。
        }
        throw BizException.keyed(ErrorCode.PARAM_INVALID, "dashboard.realtime_agent_invalid",
                new Object[]{agentId}, "unsupported dashboard realtime agent: " + agentId);
    }

    private Date costSince(int range) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -range);
        return cal.getTime();
    }

    private CostKpiVO buildCostKpi(int range, Long agentId, BigDecimal total, int creditUsageWorkitems) {
        CostKpiVO cost = new CostKpiVO();
        cost.setRange(range);
        cost.setAgentId(agentId == null ? AGENT_ALL : String.valueOf(agentId));
        cost.setTotal(total);
        cost.setPerTask(creditUsageWorkitems > 0
                ? total.divide(BigDecimal.valueOf(creditUsageWorkitems), 2, RoundingMode.HALF_UP)
                : scale2(BigDecimal.ZERO));
        cost.setPerDay(total.divide(BigDecimal.valueOf(range), 2, RoundingMode.HALF_UP));
        return cost;
    }

    private CostCompositionVO buildCostComposition(int range, BigDecimal total,
                                                   List<InsightMetricsVO.CreditSource> rows) {
        CostCompositionVO vo = new CostCompositionVO();
        vo.setRange(range);
        vo.setScope(SCOPE_ALL_AGENTS);
        vo.setTotal(total);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            vo.setSources(new ArrayList<>());
            return vo;
        }

        Map<String, BigDecimal> creditsBySource = new HashMap<>();
        if (rows != null) {
            for (InsightMetricsVO.CreditSource row : rows) {
                if (row == null || row.getSource() == null || row.getCredits() == null
                        || row.getCredits().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                creditsBySource.merge(row.getSource(), row.getCredits(), BigDecimal::add);
            }
        }

        List<CostCompositionVO.Source> sources = new ArrayList<>();
        for (CreditSourceMeta meta : CREDIT_SOURCE_META) {
            BigDecimal credits = creditsBySource.get(meta.source());
            if (credits == null || credits.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            CostCompositionVO.Source source = new CostCompositionVO.Source();
            source.setKey(meta.key());
            source.setName(meta.name());
            source.setCredits(scale2(credits));
            sources.add(source);
        }
        sources.sort(Comparator.comparing(CostCompositionVO.Source::getCredits).reversed());

        if (!sources.isEmpty()) {
            // 各来源独立保留两位小数后可能与总数相差分位，调整最大来源以维持 total = sum(sources)。
            BigDecimal roundedSum = sources.stream()
                    .map(CostCompositionVO.Source::getCredits)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal delta = total.subtract(roundedSum);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                CostCompositionVO.Source largest = sources.get(0);
                largest.setCredits(largest.getCredits().add(delta));
            }
            for (CostCompositionVO.Source source : sources) {
                source.setShare(source.getCredits().divide(total, 4, RoundingMode.HALF_UP));
            }
        }
        vo.setSources(sources);
        return vo;
    }

    private static BigDecimal scale2(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public List<RunningTaskVO> getAgentRunning(long tenantId, long agentId) {
        if (dao.agentExists(tenantId, agentId) == 0) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        return dao.listAgentRunning(tenantId, agentId);
    }

    public List<CompletedWorkitemVO> getTodayCompleted(long tenantId) {
        return dao.listTodayCompletedWorkitems(tenantId);
    }

    public List<CompletedWorkitemVO> getWeekCompleted(long tenantId) {
        return dao.listWeekCompletedWorkitems(tenantId);
    }

    public List<RunningWorkitemVO> getRunningWorkitems(long tenantId) {
        return dao.listRunningWorkitems(tenantId);
    }

    public OverviewVO getOverview(long tenantId) {
        OverviewVO vo = new OverviewVO();
        OverviewKpiVO kpi = vo.getKpi();
        kpi.setTodayCreatedWorkitems(dao.countTodayCreatedWorkitems(tenantId));
        kpi.setRunningWorkitems(dao.countRunningWorkitems(tenantId));
        kpi.setTodayCompletedTasks(dao.countTodayCompletedTasks(tenantId));
        kpi.setWeekCompletedTasks(dao.countWeekCompletedTasks(tenantId));
        vo.setInventory(buildInventory(tenantId));
        vo.setGeneratedAt(Instant.now().toString());
        return vo;
    }

    public PipelineHealthVO getPipelineHealth(long tenantId, String range) {
        if (range == null || !PIPELINE_RANGES.contains(range)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "dashboard.pipeline_health_range_invalid",
                    new Object[]{range == null ? "" : range},
                    "unsupported dashboard pipeline-health range: " + range);
        }
        PipelineWindow window = pipelineWindow(range, LocalDate.now(DASHBOARD_ZONE));
        PipelineHealthVO vo = new PipelineHealthVO();
        vo.setRange(range);

        Map<String, Object> execution = dao.pipelineExecutionStats(tenantId, window.start(), window.end());
        int succeeded = execution == null ? 0 : intVal(execution.get("succeeded"));
        int total = execution == null ? 0 : intVal(execution.get("total"));
        SuccessSubVO successSub = new SuccessSubVO();
        successSub.setSucceeded(succeeded);
        successSub.setTotal(total);
        vo.setSuccessSub(successSub);
        vo.setSuccessRate(total > 0 ? round1((double) succeeded / total * 100.0) : null);
        vo.setFailedOrTimeout(execution == null ? 0 : intVal(execution.get("failedOrTimeout")));

        Map<String, Object> reworkStats = dao.pipelineReworkStats(tenantId, window.start(), window.end());
        ReworkVO rework = new ReworkVO();
        rework.setReworkedWorkitems(reworkStats == null ? 0 : intVal(reworkStats.get("reworkedWorkitems")));
        rework.setExecutedWorkitems(reworkStats == null ? 0 : intVal(reworkStats.get("executedWorkitems")));
        rework.setRetryCount(reworkStats == null ? 0 : intVal(reworkStats.get("retryCount")));
        rework.setRate(rework.getExecutedWorkitems() > 0
                ? round1((double) rework.getReworkedWorkitems() / rework.getExecutedWorkitems() * 100.0) : null);
        vo.setRework(rework);

        vo.setAvgWorkitemDurationMinutes(avgWorkitemDurationMinutes(tenantId, window));
        vo.setGeneratedAt(Instant.now().toString());
        return vo;
    }

    private Integer avgWorkitemDurationMinutes(long tenantId, PipelineWindow window) {
        if ("SUCCESS_AT".equalsIgnoreCase(avgSource)) {
            return dao.avgPipelineWorkitemDurationMinutes(tenantId, window.start(), window.end());
        }
        // 完成类指标只允许经共享事实层取数，保证与人机协作平均完成时长逐字符一致。
        return completionFactQuery.averageMinutes(tenantId, window.startDate(), window.endDateInclusive());
    }

    static PipelineWindow pipelineWindow(String range, LocalDate day) {
        return switch (range) {
            case "today" -> new PipelineWindow(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
            case "7d" -> new PipelineWindow(day.minusDays(7).atStartOfDay(), day.atStartOfDay());
            case "30d" -> new PipelineWindow(day.minusDays(30).atStartOfDay(), day.atStartOfDay());
            case "90d" -> new PipelineWindow(day.minusDays(90).atStartOfDay(), day.atStartOfDay());
            default -> throw new IllegalArgumentException("unsupported pipeline range: " + range);
        };
    }

    record PipelineWindow(LocalDateTime start, LocalDateTime end) {
        LocalDate startDate() {
            return start.toLocalDate();
        }

        LocalDate endDateInclusive() {
            return end.toLocalDate().minusDays(1);
        }
    }

    private KpiVO buildKpi(long tenantId) {
        KpiVO kpi = new KpiVO();
        int running = dao.countRunningDispatches(tenantId);
        int online = dao.countOnlineAgents(tenantId);
        Integer avgTaskDuration = dao.avgTodayCompletedTaskDurationMinutes(tenantId);
        kpi.setRunningDispatches(running);
        kpi.setTodayCompletedTasks(dao.countTodayCompletedTasks(tenantId));
        kpi.setWeekCompletedTasks(dao.countWeekCompletedTasks(tenantId));
        kpi.setAvgTaskDurationMinutes(avgTaskDuration != null ? avgTaskDuration : 0);
        kpi.setInProgressWorkitems(dao.countInProgressWorkitems(tenantId));
        kpi.setQueuedDispatches(dao.countQueuedDispatches(tenantId));
        kpi.setActiveSquads(dao.countActiveSquads(tenantId));
        kpi.setOnlineAgents(online);
        kpi.setAvgLoad(online > 0 ? round2((double) running / online) : 0.0);
        return kpi;
    }

    private InventoryVO buildInventory(long tenantId) {
        InventoryVO inv = new InventoryVO();
        for (Map<String, Object> row : dao.countWorkitemsByLifecycle(tenantId)) {
            String category = str(row.get("category"));
            int cnt = intVal(row.get("cnt"));
            if ("INIT".equals(category)) {
                inv.getByLifecycle().setInit(cnt);
            } else if ("IN_PROGRESS".equals(category)) {
                inv.getByLifecycle().setInProgress(cnt);
            } else if ("DONE".equals(category)) {
                inv.getByLifecycle().setDone(cnt);
            } else if ("CANCELED".equals(category)) {
                inv.getByLifecycle().setCanceled(cnt);
            }
        }
        for (Map<String, Object> row : dao.countWorkitemsByType(tenantId)) {
            String type = str(row.get("workType"));
            int cnt = intVal(row.get("cnt"));
            if ("REQ".equals(type)) {
                inv.getByType().setReq(cnt);
            } else if ("TASK".equals(type)) {
                inv.getByType().setTask(cnt);
            } else if ("BUG".equals(type)) {
                inv.getByType().setBug(cnt);
            }
        }
        return inv;
    }

    private List<SquadLineVO> buildSquads(long tenantId) {
        Map<Long, Integer> inProgressBySquad = new HashMap<>();
        for (Map<String, Object> row : dao.squadInProgressWorkitems(tenantId)) {
            inProgressBySquad.put(longVal(row.get("squadId")), intVal(row.get("cnt")));
        }
        List<SquadLineVO> result = new ArrayList<>();
        for (Map<String, Object> row : dao.squadLineAggregates(tenantId)) {
            SquadLineVO vo = new SquadLineVO();
            Long squadId = longVal(row.get("squadId"));
            int members = intVal(row.get("members"));
            int runningTasks = intVal(row.get("runningTasks"));
            vo.setSquadId(squadId);
            vo.setName(str(row.get("name")));
            vo.setMembers(members);
            vo.setOnline(intVal(row.get("online")));
            vo.setBusy(intVal(row.get("busy")));
            vo.setRunningTasks(runningTasks);
            vo.setInProgressWorkitems(inProgressBySquad.getOrDefault(squadId, 0));
            vo.setLoad(members > 0 ? round2((double) runningTasks / members) : 0.0);
            result.add(vo);
        }
        return result;
    }

    private List<WorkstationVO> buildWorkstations(long tenantId) {
        List<WorkstationVO> result = new ArrayList<>();
        for (Map<String, Object> row : dao.onlineWorkstations(tenantId)) {
            WorkstationVO vo = new WorkstationVO();
            int runningTasks = intVal(row.get("runningTasks"));
            vo.setAgentId(longVal(row.get("agentId")));
            vo.setName(str(row.get("name")));
            vo.setAvatarUrl(str(row.get("avatarUrl")));
            vo.setRunningTasks(runningTasks);
            vo.setBusy(runningTasks > 0);
            result.add(vo);
        }
        return result;
    }

    private HealthVO buildHealth(long tenantId) {
        HealthVO health = new HealthVO();
        int succeeded = dao.countTodaySucceeded(tenantId);
        int failedOrTimeout = dao.countTodayFailedOrTimeout(tenantId);
        int terminal = succeeded + failedOrTimeout;
        health.setSuccessRate(terminal > 0 ? round1((double) succeeded / terminal * 100.0) : 0.0);
        health.setFailedOrTimeout(failedOrTimeout);
        health.setRetries(dao.countTodayRetries(tenantId));
        Integer avg = dao.avgTodaySuccessDurationMinutes(tenantId);
        health.setAvgDurationMinutes(avg != null ? avg : 0);
        return health;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int intVal(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Long longVal(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
