package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 用户详情内的单条有效空间成员关系（空间名称、权限级别、加入时间）。 */
@Getter
@Setter
public class UserAdminWorkspaceVO {
    private Long workspaceId;
    private String name;
    private String accessLevel;
    /** 该空间是否以该用户为所有者；owner 不可被移除、不可被降级。 */
    private Boolean isOwner;
    private Date joinedAt;
}
