package com.aliyun.autowonder.spacedashboard;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 空间级看板：在调用者可统计的空间集合（{@link #resolveScope}）内跨空间汇总数字员工、工单交付与成本。
 * <p>
 * 权限只有一个判定点：平台管理员看到全平台未停用未删除的空间，其他调用者只看到自己负责（owner）
 * 或持有 ADMIN 角色的空间；集合为空即 403 {@code NO_PERMISSION}。前端不传空间列表。
 * <p>
 * 工单与进行中按创建时间统计，已完成按最近一次进入 DONE 节点的状态变更时间统计，三者基准不同、
 * 不可相加；单任务 Credits 的分母固定为交付窗口内全部类型的已完成数，与交付版块的类型筛选无关。
 * 日期边界与窗口天数均按 Asia/Shanghai 自然日、左闭右开 [start 00:00, end+1 00:00) 计算。
 */
@Service
public class SpaceDashboardService {

    static final List<String> ALL_WORK_TYPES = List.of("REQ", "BUG", "TASK");
    /** 窗口最长 181 个自然日（近 180 天 + 当天，与 insights 页 RangePicker 一致）。 */
    static final int MAX_WINDOW_DAYS = 181;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SpaceDashboardDao dao;
    private final SystemAdminService systemAdminService;

    public SpaceDashboardService(SpaceDashboardDao dao, SystemAdminService systemAdminService) {
        this.dao = dao;
        this.systemAdminService = systemAdminService;
    }

    public SpaceDashboardVO overview(long userId, LocalDate deliveryStart, LocalDate deliveryEnd,
                                     String workTypes, LocalDate costStart, LocalDate costEnd) {
        requireWindow(deliveryStart, deliveryEnd);
        requireWindow(costStart, costEnd);
        List<String> typeFilter = normalizeWorkTypes(workTypes);

        boolean platformAdmin = systemAdminService.isSystemAdmin(userId);
        List<ScopedWorkspace> scope = resolveScope(userId, platformAdmin);
        List<Long> scopeIds = scope.stream().map(ScopedWorkspace::getId).toList();

        Date deliveryFrom = atStartOfDay(deliveryStart);
        Date deliveryTo = atStartOfDay(deliveryEnd.plusDays(1));
        Date costFrom = atStartOfDay(costStart);
        Date costTo = atStartOfDay(costEnd.plusDays(1));

        Map<Long, Long> agents = toCountMap(dao.countAgents(scopeIds));
        Map<Long, Long> squads = toCountMap(dao.countSquads(scopeIds));
        long humanCount = dao.countDistinctHumans(scopeIds);

        Map<Long, Long> created = toCountMap(dao.countCreatedWorkitems(scopeIds, deliveryFrom, deliveryTo, typeFilter));
        Map<Long, Long> completed = toCountMap(dao.countCompletedWorkitems(scopeIds, deliveryFrom, deliveryTo, typeFilter));
        Map<Long, Long> inProgress = toCountMap(
                dao.countInProgressWorkitems(scopeIds, deliveryFrom, deliveryTo, typeFilter));
        // 单任务 Credits 的分母是「不加类型筛选」的窗口内已完成数；已全选时复用同一次查询结果。
        Map<Long, Long> completedAllTypes = typeFilter.isEmpty()
                ? completed
                : toCountMap(dao.countCompletedWorkitems(scopeIds, deliveryFrom, deliveryTo, List.of()));
        Map<Long, BigDecimal> credits = toCreditsMap(dao.sumCredits(scopeIds, costFrom, costTo));

        SpaceDashboardVO vo = new SpaceDashboardVO();
        vo.setPlatformAdmin(platformAdmin);
        vo.setWorkspaceCount(scope.size());
        vo.setWorkspaces(scope.stream()
                .map(workspace -> new SpaceDashboardVO.WorkspaceItem(workspace.getId(), workspace.getName()))
                .toList());

        SpaceDashboardVO.Agents agentsVo = new SpaceDashboardVO.Agents();
        agentsVo.setWorkspaceCount(scope.size());
        agentsVo.setAgentCount(sumOverScope(scope, agents));
        agentsVo.setHumanCount(humanCount);
        agentsVo.setSquadCount(sumOverScope(scope, squads));
        vo.setAgents(agentsVo);

        SpaceDashboardVO.Delivery deliveryVo = new SpaceDashboardVO.Delivery();
        deliveryVo.setWindow(new SpaceDashboardVO.DeliveryWindow(
                deliveryStart.toString(), deliveryEnd.toString(),
                typeFilter.isEmpty() ? ALL_WORK_TYPES : typeFilter));
        deliveryVo.setWorkitemCount(sumOverScope(scope, created));
        deliveryVo.setCompletedCount(sumOverScope(scope, completed));
        deliveryVo.setInProgressCount(sumOverScope(scope, inProgress));
        CompletedRank topCompleted = completedRank(scope, completed, true);
        deliveryVo.setTopCompleted(topCompleted.items());
        deliveryVo.setTopCompletedTotal(topCompleted.total());
        CompletedRank bottomCompleted = completedRank(scope, completed, false);
        deliveryVo.setBottomCompleted(bottomCompleted.items());
        deliveryVo.setBottomCompletedTotal(bottomCompleted.total());
        vo.setDelivery(deliveryVo);

        BigDecimal totalCredits = sumCredits(scope, credits);
        SpaceDashboardVO.Cost costVo = new SpaceDashboardVO.Cost();
        costVo.setWindow(new SpaceDashboardVO.Window(costStart.toString(), costEnd.toString()));
        costVo.setTotalCredits(totalCredits);
        long windowDays = ChronoUnit.DAYS.between(costStart, costEnd) + 1;
        costVo.setDailyAverage(totalCredits.divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP));
        long completedForCost = sumOverScope(scope, completedAllTypes);
        costVo.setCompletedWorkitemCount(completedForCost);
        costVo.setPerCompletedWorkitem(completedForCost > 0
                ? totalCredits.divide(BigDecimal.valueOf(completedForCost), 4, RoundingMode.HALF_UP)
                : null);
        CreditsRank topCredits = creditsRank(scope, credits, true);
        costVo.setTopCredits(topCredits.items());
        costVo.setTopCreditsTotal(topCredits.total());
        CreditsRank bottomCredits = creditsRank(scope, credits, false);
        costVo.setBottomCredits(bottomCredits.items());
        costVo.setBottomCreditsTotal(bottomCredits.total());
        vo.setCost(costVo);

        return vo;
    }

    /** 统计范围是权限的唯一判定点：空集合意味着调用者没有任何可管理的空间。 */
    private List<ScopedWorkspace> resolveScope(long userId, boolean platformAdmin) {
        List<ScopedWorkspace> scope = dao.listScopedWorkspaces(userId, platformAdmin);
        if (scope.isEmpty()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return scope;
    }

    private static void requireWindow(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_WINDOW_DAYS) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    /** 空/缺省 = 全部类型；未知类型直接拒绝，避免静默放宽统计口径。 */
    private static List<String> normalizeWorkTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String value = token.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (!ALL_WORK_TYPES.contains(value)) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private static Date atStartOfDay(LocalDate date) {
        return Date.from(date.atStartOfDay(ZONE).toInstant());
    }

    private static Map<Long, Long> toCountMap(List<WorkspaceCount> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (WorkspaceCount row : rows) {
            result.merge(row.getWorkspaceId(), row.getTotal(), Long::sum);
        }
        return result;
    }

    private static Map<Long, BigDecimal> toCreditsMap(List<WorkspaceCredits> rows) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (WorkspaceCredits row : rows) {
            BigDecimal value = row.getCredits() == null ? BigDecimal.ZERO : row.getCredits();
            result.merge(row.getWorkspaceId(), value, BigDecimal::add);
        }
        return result;
    }

    /** 只累加 scope 内的空间，聚合里多出来的行（理论上不会出现）不会进汇总值。 */
    private static long sumOverScope(List<ScopedWorkspace> scope, Map<Long, Long> values) {
        long sum = 0L;
        for (ScopedWorkspace workspace : scope) {
            sum += values.getOrDefault(workspace.getId(), 0L);
        }
        return sum;
    }

    private static BigDecimal sumCredits(List<ScopedWorkspace> scope, Map<Long, BigDecimal> credits) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ScopedWorkspace workspace : scope) {
            BigDecimal value = credits.get(workspace.getId());
            if (value != null) {
                sum = sum.add(value);
            }
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 并列全部返回（前端只展示前 10 条，完整清单放在 tooltip）。以 scope 为基准补 0，
     * 因此窗口内 0 完成的空间会出现在「最少」排行里。
     */
    private static CompletedRank completedRank(List<ScopedWorkspace> scope, Map<Long, Long> completed, boolean max) {
        long target = max
                ? scope.stream().mapToLong(ws -> completed.getOrDefault(ws.getId(), 0L)).max().orElse(0L)
                : scope.stream().mapToLong(ws -> completed.getOrDefault(ws.getId(), 0L)).min().orElse(0L);
        List<SpaceDashboardVO.CompletedRankItem> items = new ArrayList<>();
        int tied = 0;
        for (ScopedWorkspace workspace : scope) {
            long value = completed.getOrDefault(workspace.getId(), 0L);
            if (value != target) {
                continue;
            }
            tied++;
            items.add(new SpaceDashboardVO.CompletedRankItem(workspace.getId(), workspace.getName(), value));
        }
        return new CompletedRank(items, tied);
    }

    /** 同上，但按 Credits 取最大/最小；0 消耗的空间同样参与「最少」排行。 */
    private static CreditsRank creditsRank(List<ScopedWorkspace> scope, Map<Long, BigDecimal> credits, boolean max) {
        // scope 在 resolveScope 后保证非空，首个空间即可作为比较基准。
        BigDecimal target = credits.getOrDefault(scope.get(0).getId(), BigDecimal.ZERO);
        for (ScopedWorkspace workspace : scope) {
            BigDecimal value = credits.getOrDefault(workspace.getId(), BigDecimal.ZERO);
            if (max ? value.compareTo(target) > 0 : value.compareTo(target) < 0) {
                target = value;
            }
        }
        List<SpaceDashboardVO.CreditsRankItem> items = new ArrayList<>();
        int tied = 0;
        for (ScopedWorkspace workspace : scope) {
            BigDecimal value = credits.getOrDefault(workspace.getId(), BigDecimal.ZERO);
            if (value.compareTo(target) != 0) {
                continue;
            }
            tied++;
            items.add(new SpaceDashboardVO.CreditsRankItem(workspace.getId(), workspace.getName(), value));
        }
        return new CreditsRank(items, tied);
    }

    private record CompletedRank(List<SpaceDashboardVO.CompletedRankItem> items, int total) {
    }

    private record CreditsRank(List<SpaceDashboardVO.CreditsRankItem> items, int total) {
    }
}
