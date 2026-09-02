package com.aliyun.autowonder.workspace.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceListItemVO {
    private Long id;
    private String name;
    private String description;
    private String membershipStatus;
    private String accessLevel;
    private Long pendingRequestId;
}
