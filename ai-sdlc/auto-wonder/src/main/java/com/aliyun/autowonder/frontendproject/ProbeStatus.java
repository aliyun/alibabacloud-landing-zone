package com.aliyun.autowonder.frontendproject;

/**
 * Probe run states. Mirrors the spec's generation-task lifecycle
 * (排队中 → 执行中 → 成功 / 失败 / 已取消) and is deliberately separate from
 * {@code DispatchStatus}: probing a repo is not a delivery and must not reuse that machine.
 */
public final class ProbeStatus {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";

    public static boolean isTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELED.equals(status);
    }

    /** Whether the run still owns its repo checkout, i.e. may still finish on its own. */
    public static boolean isActive(String status) {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }

    private ProbeStatus() {}
}
