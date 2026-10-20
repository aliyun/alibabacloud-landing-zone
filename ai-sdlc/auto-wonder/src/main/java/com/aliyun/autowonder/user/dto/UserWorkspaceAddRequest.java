package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

/** 管理端为指定用户添加空间成员关系：空间 + 权限档位（缺省只读）。 */
@Getter
@Setter
public class UserWorkspaceAddRequest {
    private Long workspaceId;
    /** READ_ONLY / READ_WRITE / ADMIN；为空按 READ_ONLY。 */
    private String accessLevel;
}
