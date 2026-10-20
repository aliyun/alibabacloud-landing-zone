package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import java.util.Locale;

public record AssignmentActor(String type, long ref, String displayName,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String actorNameKind) {
    public static final String AGENT_NAME_FALLBACK = "AGENT_NAME_FALLBACK";
    public static final String HUMAN_NAME_FALLBACK = "HUMAN_NAME_FALLBACK";
    public static final String SYSTEM_NAME_FALLBACK = "SYSTEM_NAME_FALLBACK";

    public AssignmentActor(String type, long ref, String displayName) {
        this(type, ref, displayName, null);
    }

    public static AssignmentActor systemFallback() {
        return new AssignmentActor("SYSTEM", 0L, "系统", SYSTEM_NAME_FALLBACK);
    }

    public static String recipientDisplayName(String raw, String kind, Locale locale) {
        String key = switch (kind == null ? "" : kind) {
            case AGENT_NAME_FALLBACK -> "notification.actor.agent_fallback";
            case HUMAN_NAME_FALLBACK -> "notification.actor.human_fallback";
            case SYSTEM_NAME_FALLBACK -> "notification.actor.system_fallback";
            default -> null;
        };
        return SystemMessages.render(key, null, raw, locale);
    }


    public static AssignmentActor human(long userId, String displayName) {
        return new AssignmentActor("HUMAN", userId, displayName);
    }

    public static AssignmentActor agent(long agentId, String displayName) {
        return new AssignmentActor("AGENT", agentId, displayName);
    }

    public static AssignmentActor system(String displayName) {
        return new AssignmentActor("SYSTEM", 0L, displayName);
    }

    public boolean isHuman() {
        return "HUMAN".equals(type);
    }
}
