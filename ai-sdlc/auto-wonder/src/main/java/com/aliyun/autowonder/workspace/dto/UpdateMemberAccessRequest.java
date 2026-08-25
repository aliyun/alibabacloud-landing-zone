package com.aliyun.autowonder.workspace.dto;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMemberAccessRequest {
    private WorkspaceAccessLevel accessLevel;
}
