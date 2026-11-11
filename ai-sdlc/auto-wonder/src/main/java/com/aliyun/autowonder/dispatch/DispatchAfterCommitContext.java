package com.aliyun.autowonder.dispatch;

/**
 * Marks the thread phase while an {@code afterCommit} callback runs. Registering a new
 * transaction synchronization there would never fire, so dispatch scheduling must execute
 * immediately on a detached thread (the business transaction is already committed).
 */
public final class DispatchAfterCommitContext {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private DispatchAfterCommitContext() {
    }

    public static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** Runs inside a fresh transaction: newly registered afterCommit callbacks are safe again. */
    public static void runOutside(Runnable action) {
        Boolean previous = ACTIVE.get();
        ACTIVE.remove();
        try {
            action.run();
        } finally {
            if (previous != null) {
                ACTIVE.set(previous);
            }
        }
    }
}