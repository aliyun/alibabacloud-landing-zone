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
    public static final String PAUSE_CONFIRMATION_MISSING = "PAUSE_CONFIRMATION_MISSING";
    /** Management-state rejection: the target agent is offline, so no new execution may start. */
    public static final String AGENT_OFFLINE = "AGENT_OFFLINE";
    /** Management-state rejection: the target SDLC is disabled, so no new execution may start. */
    public static final String SDLC_DISABLED = "SDLC_DISABLED";

    private DispatchFailureReason() {}
}
