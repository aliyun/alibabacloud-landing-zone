package com.aliyun.autowonder.integration.event;

/**
 * 工单状态变更事件。actorType 区分真人流转与数字员工流转，关注人通知据此判断
 * 是否要把操作者本人从收件人中排除（操作者已经知道自己改了什么）。
 */
public record WorkitemStatusChangedEvent(String actorType, long tenantId, long workitemId, long toNodeId,
                                         long userId) {

    public static final String ACTOR_HUMAN = "HUMAN";
    public static final String ACTOR_AGENT = "AGENT";

    public WorkitemStatusChangedEvent(long tenantId, long workitemId, long toNodeId, long userId) {
        this(ACTOR_HUMAN, tenantId, workitemId, toNodeId, userId);
    }

    public boolean isHumanActor() {
        return ACTOR_HUMAN.equals(actorType);
    }
}
