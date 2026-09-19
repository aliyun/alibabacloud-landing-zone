package com.aliyun.autowonder.workitem;

import java.util.Set;

/**
 * 定时工单的列表过滤口径与阶段派生取值。
 *
 * 阶段完全由已持久化的字段派生，不引入新存储：待触发看 scheduled_start_at 是否仍在未来，
 * 执行中看最新 dispatch 是否非终态，已完成看状态节点，其余为待处理。
 */
public final class WorkitemScheduledPhase {

    /** 过滤值：待触发与已触发的定时工单全部返回。 */
    public static final String ALL = "ALL";
    /** 过滤值 / 阶段：已设置定时且未到点。 */
    public static final String PENDING = "PENDING";
    /** 过滤值：已触发（含执行中与已完成）。 */
    public static final String TRIGGERED = "TRIGGERED";

    /** 阶段：已触发但数字员工尚未开工，即没有进行中的 dispatch。 */
    public static final String READY = "READY";
    /** 阶段：最新 dispatch 仍处于非终态。 */
    public static final String RUNNING = "RUNNING";
    /** 阶段：工单已流转到完成态状态节点。 */
    public static final String DONE = "DONE";

    private static final Set<String> FILTERS = Set.of(ALL, PENDING, TRIGGERED);

    /** 过滤值白名单校验，非法值一律视为不过滤。 */
    public static boolean isFilter(String value) {
        return value != null && FILTERS.contains(value);
    }

    private WorkitemScheduledPhase() {}
}
