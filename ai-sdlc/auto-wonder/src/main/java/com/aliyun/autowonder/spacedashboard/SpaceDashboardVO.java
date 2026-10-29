package com.aliyun.autowonder.spacedashboard;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 空间级看板响应体。三个版块共用一次请求；所有指标都只在调用者可统计的空间集合内汇总。
 */
@Getter
@Setter
public class SpaceDashboardVO {

    /** 当前调用者是否为平台管理员，前端据此展示「全平台」范围说明。 */
    private boolean platformAdmin;
    /** 统计范围内的空间数量（= {@link #workspaces} 的长度）。 */
    private int workspaceCount;
    private List<WorkspaceItem> workspaces;
    private Agents agents;
    private Delivery delivery;
    private Cost cost;

    public record WorkspaceItem(long id, String name) {
    }

    public record Window(String start, String end) {
    }

    public record DeliveryWindow(String start, String end, List<String> workTypes) {
    }

    /** 已完成工单排行项：数值为窗口内完成数。 */
    public record CompletedRankItem(long workspaceId, String workspaceName, long value) {
    }

    /** Credits 排行项：数值为窗口内消耗，保留 4 位精度。 */
    public record CreditsRankItem(long workspaceId, String workspaceName, BigDecimal value) {
    }

    @Getter
    @Setter
    public static class Agents {
        private int workspaceCount;
        /** 普通数字员工（kind = STANDARD），含全部状态，排除平台内置数字人。 */
        private long agentCount;
        /** 有效成员中的真人，跨空间去重。 */
        private long humanCount;
        /** 未解散的小队。 */
        private long squadCount;
    }

    @Getter
    @Setter
    public static class Delivery {
        private DeliveryWindow window;
        /** 窗口内创建的工单数（含已取消、已完成）。 */
        private long workitemCount;
        /** 窗口内完成（最近一次进入 DONE 节点）的工单数。 */
        private long completedCount;
        /** 窗口内创建且当前未结束的工单数。 */
        private long inProgressCount;
        private List<CompletedRankItem> topCompleted;
        /** 并列最多的空间总数；{@code topCompleted} 最多返回 10 条。 */
        private int topCompletedTotal;
        private List<CompletedRankItem> bottomCompleted;
        /** 并列最少的空间总数；包含窗口内 0 完成的空间。 */
        private int bottomCompletedTotal;
    }

    @Getter
    @Setter
    public static class Cost {
        private Window window;
        /** 窗口内全部 AI 消耗（派发执行 + 会话），NULL 按 0。 */
        private BigDecimal totalCredits;
        /** 总消耗 / 窗口自然日天数。 */
        private BigDecimal dailyAverage;
        /** 交付窗口内全部类型的已完成工单数，即单任务 Credits 的分母（与交付版块的类型筛选无关）。 */
        private long completedWorkitemCount;
        /** 总消耗 / {@link #completedWorkitemCount}；分母为 0 时为 null。 */
        private BigDecimal perCompletedWorkitem;
        private List<CreditsRankItem> topCredits;
        private int topCreditsTotal;
        private List<CreditsRankItem> bottomCredits;
        private int bottomCreditsTotal;
    }
}
