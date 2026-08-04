package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;

import java.util.Date;
import java.util.Set;

/**
 * Detects workitems that have silently entered an abnormal state: still in an
 * IN_PROGRESS status but whose latest dispatch has failed/timed out or stalled
 * with no self-healing. Such workitems otherwise look normal on the list page,
 * so this surfaces a signal for human intervention.
 */
public final class WorkitemHealthEvaluator {

    public static final String OK = "OK";
    public static final String STUCK = "STUCK";

    private static final String IN_PROGRESS_CATEGORY = "IN_PROGRESS";

    private static final Set<String> FAILED_TERMINAL = Set.of(
            DispatchStatus.FAILED, DispatchStatus.TIMEOUT, DispatchStatus.CANCELED);

    private WorkitemHealthEvaluator() {}

    public record Result(String health, String reason) {
        static Result ok() {
            return new Result(OK, null);
        }

        static Result stuck(String reason) {
            return new Result(STUCK, reason);
        }
    }

    /**
     * @param statusCategory   status node category of the workitem (e.g. IN_PROGRESS)
     * @param latest           latest dispatch of the workitem, or null if none
     * @param nowMs            current epoch millis
     * @param stuckThresholdMs how long a non-terminal dispatch may be idle before it counts as stalled
     */
    public static Result evaluate(String statusCategory, DispatchDO latest, long nowMs, long stuckThresholdMs) {
        // Only workitems actively in progress can be "stuck"; terminal/new states are not.
        if (!IN_PROGRESS_CATEGORY.equals(statusCategory) || latest == null) {
            return Result.ok();
        }
        String status = latest.getStatus();
        if (FAILED_TERMINAL.contains(status)) {
            return Result.stuck("最近一次执行" + failureLabel(status) + "，流程已停止且无自动恢复，请人工介入");
        }
        if (!DispatchStatus.isTerminal(status)) {
            Date modified = latest.getGmtModified();
            if (modified != null && nowMs - modified.getTime() > stuckThresholdMs) {
                long minutes = (nowMs - modified.getTime()) / 60_000L;
                return Result.stuck("执行已卡住超过 " + minutes + " 分钟无进展，请人工介入");
            }
        }
        return Result.ok();
    }

    private static String failureLabel(String status) {
        if (DispatchStatus.TIMEOUT.equals(status)) {
            return "超时";
        }
        if (DispatchStatus.CANCELED.equals(status)) {
            return "被取消";
        }
        return "失败";
    }
}
