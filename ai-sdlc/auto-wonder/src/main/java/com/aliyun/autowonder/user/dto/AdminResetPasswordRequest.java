package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

/** 管理员重置密码：两次输入一致性由前端保证，后端复核（含空值与长度门槛）。 */
@Getter
@Setter
public class AdminResetPasswordRequest {
    private String newPassword;
    private String confirmPassword;
}
