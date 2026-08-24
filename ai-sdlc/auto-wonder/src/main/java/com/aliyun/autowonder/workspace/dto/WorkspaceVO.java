package com.aliyun.autowonder.workspace.dto;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceVO {
    private Long id;
    private String name;
    private String description;
    private WorkspaceAccessLevel accessLevel;
}
