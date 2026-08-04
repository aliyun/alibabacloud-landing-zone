package com.aliyun.autowonder.workitem;

public record AssignmentActor(String type, long ref, String displayName) {

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
