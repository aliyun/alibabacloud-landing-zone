package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 平台用户管理的列表行与详情共用视图。已删除用户的 username/email 已替换为删除前快照
 * （{@code deleted_username} / {@code deleted_email}），前端无需感知快照字段。
 */
@Getter
@Setter
public class UserAdminUserVO {
    private Long id;
    /** 展示用用户名：已删除用户为删除前原名快照。 */
    private String username;
    private String nickname;
    /** 展示用邮箱：已删除用户为原邮箱快照，原值为空时保持 null。 */
    private String email;
    private String phone;
    /** NORMAL / DEACTIVATED / DELETED（与 UserAccountStates 口径一致）。 */
    private String status;
    private Boolean isAdmin;
    private Date createdAt;
    private Date lastLoginAt;
    private Date deletedAt;
    private Long deletedById;
    /** 删除操作人展示名（已删除用户才非空；本人注销即本人）。 */
    private String deletedByName;
    /** 仅正常用户可编辑资料/重置密码/删除/管理空间权限与工号。 */
    private Boolean editable;
    private Boolean restorable;
}
