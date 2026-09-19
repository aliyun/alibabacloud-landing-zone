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
    /** Wrapper type so Jackson emits {@code isOwner} rather than {@code owner}; see WorkspaceVO. */
    private Boolean isOwner;
    private Boolean canManage;
    /** Optimistic lock echoed to the edit modal, same as WorkspaceVO.version. */
    private Integer version;
}
