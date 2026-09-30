package com.aliyun.autowonder.dispatch;

/** 派发成功终态后发布（AFTER_COMMIT）；产物上传已完成，监听者可安全固化分享快照。 */
public class DispatchSucceededEvent {
    private final long tenantId;
    private final long workitemId;
    private final long dispatchId;
    private final Long agentId;

    public DispatchSucceededEvent(long tenantId, long workitemId, long dispatchId, Long agentId) {
        this.tenantId = tenantId;
        this.workitemId = workitemId;
        this.dispatchId = dispatchId;
        this.agentId = agentId;
    }

    public long getTenantId() {
        return tenantId;
    }

    public long getWorkitemId() {
        return workitemId;
    }

    public long getDispatchId() {
        return dispatchId;
    }

    public Long getAgentId() {
        return agentId;
    }
}
