package com.aliyun.autowonder.auth.federation;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 外部身份到 AutoWonder 账号的绑定：唯一键为 (provider_id, issuer, subject)。 */
@Getter
@Setter
public class FederatedIdentityDO {
    private Long id;
    private Long providerId;
    private String issuer;
    private String subject;
    private Long userId;
    private String displayName;
    private String email;
    private Date lastLoginAt;
    private Date gmtCreate;
    private Date gmtModified;
}
