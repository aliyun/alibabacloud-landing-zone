package com.aliyun.autowonder.dispatch;

/** Closed scheduling outcomes: retryable states remain queued; permanent states fail visibly. */
public enum DispatchWaitingReason {
    NO_EXECUTOR_CAPACITY(true, "等待可用执行器容量"),
    CAPACITY_LOCK_BUSY(true, "等待调度容量锁"),
    EXECUTOR_AT_CAPACITY(true, "执行器容量已满，等待重试"),
    EXECUTOR_RECOVERING(true, "执行器正在恢复本地任务"),
    NO_EXECUTOR_ONLINE(true, "等待执行器上线"),
    AGENT_NOT_PUBLISHED(false, "数字员工尚未发布"),
    AGENT_VERSION_NOT_FOUND(false, "发布版本不存在"),
    RUNTIME_INCOMPATIBLE(false, "执行器版本不兼容"),
    SELECTION_INTERNAL_ERROR(false, "调度器内部错误，任务未派发");

    private final boolean retryable;
    private final String userMessage;

    DispatchWaitingReason(boolean retryable, String userMessage) {
        this.retryable = retryable;
        this.userMessage = userMessage;
    }

    public boolean retryable() { return retryable; }
    public String userMessage() { return userMessage; }
    public String error() { return name() + ": " + userMessage; }
}
