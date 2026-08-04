package com.aliyun.autowonder.dispatch;

import java.util.Set;

public final class DispatchStatus {
    public static final String PENDING = "PENDING";
    public static final String PACKAGING = "PACKAGING";
    public static final String DISPATCHED = "DISPATCHED";
    public static final String ACKED = "ACKED";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSING = "PAUSING";
    public static final String PAUSED = "PAUSED";
    public static final String PAUSE_FAILED = "PAUSE_FAILED";
    public static final String WAITING_FOR_PAUSE = "WAITING_FOR_PAUSE";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String CANCELED = "CANCELED";

    private static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, TIMEOUT, CANCELED);

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }

    public static boolean isPauseable(String status) {
        return DISPATCHED.equals(status) || ACKED.equals(status) || RUNNING.equals(status);
    }

    private DispatchStatus() {}
}
