package com.aliyun.autowonder.workspace.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateWorkspaceRequest {
    private String name;
    private String description;
    private String background;
}
