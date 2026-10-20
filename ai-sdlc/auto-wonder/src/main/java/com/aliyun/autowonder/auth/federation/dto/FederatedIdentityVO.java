package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 某个 AutoWonder 账号已绑定的外部身份（个人设置页多身份管理）。 */
@Getter
@Setter
public class FederatedIdentityVO {
    private Long id;
    private Long providerId;
    private String providerName;
    private String protocol;
    /** 绑定键：验证通过的 Issuer 与稳定主体标识；仅本人可见。 */
    private String issuer;
    private String subject;
    private String displayName;
    private String email;
    private Date lastLoginAt;
    private Date gmtCreate;
}
