package com.aliyun.autowonder.workspace.dto;

import com.aliyun.autowonder.access.WorkspaceDomain;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class WorkspaceRoleVO {
    private Long id;
    private String code;
    private String name;
    private boolean administrator;
    private boolean builtIn;
    private List<WorkspaceDomain> readOnlyDomains;
    private Integer version;
    private int memberCount;
}
