package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/** 更新协议连接：null 字段保持现状；clientSecret 为空串或 null 表示沿用已存密钥。 */
@Getter
@Setter
public class UpdateConnectionRequest {
    private String name;
    private Integer enabled;
    private String issuer;
    private String samlSsoUrl;
    private String samlCert;
    private String oidcAuthorizeUrl;
    private String oidcTokenUrl;
    private String oidcJwksUrl;
    private String clientId;
    private String clientSecret;
    private String scopes;
    private String subjectClaim;
    private String nameClaim;
    private String emailClaim;
    private String allowedTenants;
    private Integer sortOrder;
}
