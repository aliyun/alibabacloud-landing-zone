package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserProfileVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    /** 只读：当前登录用户是否可以创建空间（服务端最终会再次校验）。 */
    private boolean canCreateWorkspace;
}
