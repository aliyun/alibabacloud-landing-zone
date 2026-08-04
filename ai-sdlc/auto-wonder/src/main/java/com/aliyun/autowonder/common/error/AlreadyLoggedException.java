package com.aliyun.autowonder.common.error;

public final class AlreadyLoggedException extends RuntimeException {
    private AlreadyLoggedException(String safeMessage, StackTraceElement[] stackTrace) {
        super(safeMessage, null, false, true);
        setStackTrace(stackTrace == null ? new StackTraceElement[0] : stackTrace.clone());
    }

    public static AlreadyLoggedException from(Throwable failure) {
        if (failure == null) {
            return new AlreadyLoggedException(
                    "Unexpected failure details redacted", new StackTraceElement[0]);
        }
        return new AlreadyLoggedException(
                failure.getClass().getSimpleName() + " details redacted",
                failure.getStackTrace());
    }
}
