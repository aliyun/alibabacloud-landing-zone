package com.aliyun.autowonder.workitem;

/**
 * 工单存在于另一个项目，且当前用户有权访问该项目。
 * 详情接口据此提示切换项目，而不是把项目不匹配降级成「工单不存在」（单 55699）。
 */
public class WorkitemInOtherWorkspaceException extends RuntimeException {

    private final long workitemId;
    private final long workspaceId;
    private final String workspaceName;

    public WorkitemInOtherWorkspaceException(long workitemId, long workspaceId, String workspaceName) {
        super("workitem " + workitemId + " belongs to workspace " + workspaceId);
        this.workitemId = workitemId;
        this.workspaceId = workspaceId;
        this.workspaceName = workspaceName;
    }

    public long getWorkitemId() {
        return workitemId;
    }

    public long getWorkspaceId() {
        return workspaceId;
    }

    public String getWorkspaceName() {
        return workspaceName;
    }
}
