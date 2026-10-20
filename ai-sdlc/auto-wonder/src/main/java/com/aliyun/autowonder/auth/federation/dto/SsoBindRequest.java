package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/** 首次绑定：用已有 AutoWonder 账号的用户名密码认领当前已验证的企业身份。 */
@Getter
@Setter
public class SsoBindRequest {
    private String ticket;
    private String username;
    private String password;
}
