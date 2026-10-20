package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.WorkitemUsageRunVO;
import com.aliyun.autowonder.aiusage.dto.WorkitemUsageSummaryVO;
import com.aliyun.autowonder.dispatch.DispatchDO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工单级 credits 汇总：同一数字人被 CR 打回后重跑会产生多个 dispatch，
 * 这里按 dispatch 的时间顺序为每个数字人编号 run，明细与总计口径一致。
 */
public final class WorkitemUsageAggregator {

    private static final Long NO_AGENT_KEY = Long.MIN_VALUE;

    private WorkitemUsageAggregator() {
    }

    /**
     * @param usageRows         dispatch_ai_usage 行
     * @param dispatches        本工单的派发列表，run 轮次按其时间顺序编号
     * @param agentNameResolver agentId -> 展示名，可为 null
     * @return 没有任何正数 credits 时返回 null，调用方据此不展示
     */
    public static WorkitemUsageSummaryVO summarize(List<DispatchAiUsageDO> usageRows,
                                                   List<DispatchDO> dispatches,
                                                   Function<Long, String> agentNameResolver) {
        Map<Long, BigDecimal> creditsByDispatchId = new LinkedHashMap<>();
        Map<Long, Long> agentByDispatchId = new HashMap<>();
        for (DispatchAiUsageDO row : usageRows == null ? List.<DispatchAiUsageDO>of() : usageRows) {
            if (row == null || row.getDispatchId() == null || row.getCredits() == null) {
                continue;
            }
            creditsByDispatchId.merge(row.getDispatchId(), row.getCredits(), BigDecimal::add);
            if (row.getAgentId() != null) {
                agentByDispatchId.putIfAbsent(row.getDispatchId(), row.getAgentId());
            }
        }
        if (creditsByDispatchId.isEmpty()) {
            return null;
        }

        List<DispatchDO> ordered = orderDispatches(dispatches);
        Map<Long, Integer> nextRunByAgent = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        List<WorkitemUsageRunVO> runs = new ArrayList<>();
        for (DispatchDO dispatch : ordered) {
            BigDecimal credits = creditsByDispatchId.remove(dispatch.getId());
            int runIndex = nextRunByAgent.merge(agentKey(dispatch.getAgentId()), 1, Integer::sum);
            if (credits == null) {
                continue;
            }
            total = total.add(credits);
            addRun(runs, dispatch.getAgentId(), runIndex, credits, agentNameResolver);
        }

        // usage 行里存在但派发列表缺失的 dispatch：轮次续在该数字人已有轮次之后，保证总计等于明细之和
        for (Map.Entry<Long, BigDecimal> entry : new TreeMap<>(creditsByDispatchId).entrySet()) {
            Long agentId = agentByDispatchId.get(entry.getKey());
            int runIndex = nextRunByAgent.merge(agentKey(agentId), 1, Integer::sum);
            total = total.add(entry.getValue());
            addRun(runs, agentId, runIndex, entry.getValue(), agentNameResolver);
        }

        if (total.compareTo(BigDecimal.ZERO) <= 0 || runs.isEmpty()) {
            return null;
        }
        WorkitemUsageSummaryVO summary = new WorkitemUsageSummaryVO();
        summary.setCredits(total);
        summary.setRuns(runs);
        return summary;
    }

    private static void addRun(List<WorkitemUsageRunVO> runs, Long agentId, int runIndex,
                               BigDecimal credits, Function<Long, String> agentNameResolver) {
        if (credits.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String agentName = agentNameResolver == null || agentId == null
                ? null : agentNameResolver.apply(agentId);
        WorkitemUsageRunVO run = new WorkitemUsageRunVO();
        run.setAgentId(agentId);
        run.setAgentName(agentName);
        run.setRunIndex(runIndex);
        run.setLabel(displayName(agentId, agentName) + " run-" + runIndex);
        run.setLabelKind("AGENT_RUN");
        run.setCredits(credits);
        runs.add(run);
    }

    private static String displayName(Long agentId, String agentName) {
        if (agentName != null && !agentName.isBlank()) {
            return agentName.trim();
        }
        return agentId == null ? "unknown" : "agent-" + agentId;
    }

    private static List<DispatchDO> orderDispatches(List<DispatchDO> dispatches) {
        if (dispatches == null || dispatches.isEmpty()) {
            return List.of();
        }
        return dispatches.stream()
                .filter(dispatch -> dispatch != null && dispatch.getId() != null)
                .sorted(Comparator.comparing(DispatchDO::getGmtCreate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DispatchDO::getId))
                .collect(Collectors.toList());
    }

    private static Long agentKey(Long agentId) {
        return agentId == null ? NO_AGENT_KEY : agentId;
    }
}
