package com.aliyun.autowonder.im.dto;

/** One Workspace the current user may view that occupies a robot identity. */
public class ImConflictWorkspaceView {
    private Long workspaceId;
    private String workspaceName;

    public ImConflictWorkspaceView() {
    }

    public ImConflictWorkspaceView(Long workspaceId, String workspaceName) {
        this.workspaceId = workspaceId;
        this.workspaceName = workspaceName;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getWorkspaceName() {
        return workspaceName;
    }

    public void setWorkspaceName(String workspaceName) {
        this.workspaceName = workspaceName;
    }
}
