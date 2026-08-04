package com.aliyun.autowonder.dispatch;

public final class DispatchFailureReason {
    public static final String AGENT_NOT_ONLINE = "AGENT_NOT_ONLINE";
    public static final String NO_EXECUTOR = "NO_EXECUTOR";
    public static final String REPO_PERM_MISSING = "REPO_PERM_MISSING";
    public static final String NO_AGENT_FOR_ROLE = "NO_AGENT_FOR_ROLE";
    public static final String ROLE_MISMATCH = "ROLE_MISMATCH";
    public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String MANUAL_CONTINUE = "MANUAL_CONTINUE";
    public static final String COMMENT_REWORK = "COMMENT_REWORK";

    private DispatchFailureReason() {}
}
