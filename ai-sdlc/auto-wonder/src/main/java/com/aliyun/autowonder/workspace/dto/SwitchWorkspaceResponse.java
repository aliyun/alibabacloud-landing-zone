package com.aliyun.autowonder.workspace.dto;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SwitchWorkspaceResponse {
    private String accessToken;
    private WorkspaceAccessLevel accessLevel;

    public SwitchWorkspaceResponse(String accessToken, WorkspaceAccessLevel accessLevel) {
        this.accessToken = accessToken;
        this.accessLevel = accessLevel;
    }
}
