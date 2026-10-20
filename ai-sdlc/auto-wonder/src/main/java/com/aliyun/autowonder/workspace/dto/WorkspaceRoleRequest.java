package com.aliyun.autowonder.workspace.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class WorkspaceRoleRequest {
    private String name;
    private List<String> readOnlyDomains;
    private Integer version;
}
