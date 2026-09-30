package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;

import java.util.List;
import java.util.Locale;

/**
 * 工单看板分类统一口径（工单 #55395 规格 3.1/3.2）。
 *
 * <p>分类输入：节点类别 + 当前指派 + 派发事实 + 运行态；状态名称只承担展示职责，不参与判断。
 * 优先级自上而下，命中即止：CANCELED（默认隐藏）&gt; DONE &gt; 待决策 &gt; 执行中 &gt; 待处理。
 * 「需人工」标签与「待决策」列同条件（{@link #CATEGORY_PENDING_DECISION}）。
 *
 * <p>本类是服务端唯一分类实现；WorkitemDao.xml 的 kanbanCategoryExpr SQL 表达式必须与本类保持同一语义。
 */
public final class WorkitemClassificationEvaluator {

    /** 取消态：默认隐藏，仅在筛选「已取消」时单独可见。 */
    public static final String CATEGORY_CANCELED = "CANCELED";
    /** 已完成：节点类别 DONE。 */
    public static final String CATEGORY_DONE = "DONE";
    /** 待决策：未结束且已启动过派发，已交给真人，或外部负责人下已无活跃派发。 */
    public static final String CATEGORY_PENDING_DECISION = "PENDING_DECISION";
    /** 执行中：当前存在活跃派发，或节点类别 IN_PROGRESS。 */
    public static final String CATEGORY_IN_PROGRESS = "IN_PROGRESS";
    /** 待处理：其余（节点类别 INIT 或未识别类别）。 */
    public static final String CATEGORY_NEW = "NEW";

    /** 节点初始类别。 */
    public static final String NODE_CATEGORY_INIT = "INIT";
    /** 节点进行中类别。 */
    public static final String NODE_CATEGORY_IN_PROGRESS = "IN_PROGRESS";

    private WorkitemClassificationEvaluator() {}

    /**
     * 派发已成功启动过（曾实际送达执行方）：离开排队/打包阶段之后的全部状态。
     * 仅 PENDING/PACKAGING 不算启动。
     */
    public static boolean dispatchStarted(String dispatchStatus) {
        return dispatchStatus != null
                && !DispatchStatus.PENDING.equals(dispatchStatus)
                && !DispatchStatus.PACKAGING.equals(dispatchStatus);
    }

    /** 当前活跃派发（已成功启动且未到终态，含暂停族状态）：排队/打包中不算活跃。 */
    public static boolean dispatchActive(String dispatchStatus) {
        return dispatchStarted(dispatchStatus) && !DispatchStatus.isTerminal(dispatchStatus);
    }

    /** 已结束（供统计/清理等消费者复用）：节点类别 DONE 或 CANCELED。 */
    public static boolean isFinishedCategory(String nodeCategory) {
        String category = normalize(nodeCategory);
        return CATEGORY_DONE.equals(category) || CATEGORY_CANCELED.equals(category);
    }

    /** 在途（未结束）：节点类别 INIT 或 IN_PROGRESS。 */
    public static boolean isInFlightCategory(String nodeCategory) {
        String category = normalize(nodeCategory);
        return NODE_CATEGORY_INIT.equals(category) || NODE_CATEGORY_IN_PROGRESS.equals(category);
    }

    /** 节点处于初始类别：启动交付自动推进（规格 3.3）的触发条件。 */
    public static boolean isInitCategory(String nodeCategory) {
        return NODE_CATEGORY_INIT.equals(normalize(nodeCategory));
    }

    /**
     * 统一分类。{@code dispatches} 为该工单的全部历史派发（可为 null，视作从未派发）。
     * 真人指派优先于历史活跃记录；外部负责人仅在无活跃派发时等待人工认领。
     */
    public static String classify(String nodeCategory, String assigneeType, Long assigneeRef,
                                  List<DispatchDO> dispatches) {
        boolean started = false;
        boolean active = false;
        if (dispatches != null) {
            for (DispatchDO d : dispatches) {
                if (d == null) {
                    continue;
                }
                started |= dispatchStarted(d.getStatus());
                active |= dispatchActive(d.getStatus());
            }
        }
        boolean decisionAssignee = isHumanAssignee(assigneeType, assigneeRef)
                || ("EXTERNAL".equals(assigneeType) && !active);
        return classify(nodeCategory, decisionAssignee, started, active);
    }

    /** 当前责任人为真人：指派类型 HUMAN 且指派对象非空。 */
    public static boolean isHumanAssignee(String assigneeType, Long assigneeRef) {
        return "HUMAN".equals(assigneeType) && assigneeRef != null;
    }

    /**
     * 统一分类的布尔入参形态，便于单测与 SQL 语义对照。
     *
     * @param nodeCategory     当前状态节点类别（INIT/IN_PROGRESS/DONE/CANCELED，大小写不敏感）
     * @param decisionAssignee 已指派真人，或外部负责人下已无活跃派发
     * @param dispatchStarted  历史上成功启动过派发
     * @param activeDispatch   当前存在活跃派发
     */
    public static String classify(String nodeCategory, boolean decisionAssignee,
                                  boolean dispatchStarted, boolean activeDispatch) {
        String category = normalize(nodeCategory);
        if (CATEGORY_CANCELED.equals(category)) {
            return CATEGORY_CANCELED;
        }
        if (CATEGORY_DONE.equals(category)) {
            return CATEGORY_DONE;
        }
        // 已转交真人的工单不被历史 PAUSED 等记录挡在「执行中」。
        if (isInFlightCategory(category) && decisionAssignee && dispatchStarted) {
            return CATEGORY_PENDING_DECISION;
        }
        if (activeDispatch) {
            return CATEGORY_IN_PROGRESS;
        }
        if (NODE_CATEGORY_IN_PROGRESS.equals(category)) {
            return CATEGORY_IN_PROGRESS;
        }
        return CATEGORY_NEW;
    }

    private static String normalize(String nodeCategory) {
        return nodeCategory == null ? "" : nodeCategory.trim().toUpperCase(Locale.ROOT);
    }
}
