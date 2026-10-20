package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 平台管理员视角的协议连接。clientSecret 只回显掩码，
 * 明文与密文都不出接口；更新时留空表示沿用已存值。
 */
@Getter
@Setter
public class FederationConnectionVO {
    private Long id;
    private Long idpId;
    private String idpName;
    private String name;
    private String protocol;
    private Integer enabled;
    private String issuer;
    private String samlSsoUrl;
    private String samlCert;
    private String oidcAuthorizeUrl;
    private String oidcTokenUrl;
    private String oidcJwksUrl;
    private String clientId;
    private String clientSecretMasked;
    private Boolean clientSecretConfigured;
    private String scopes;
    private String subjectClaim;
    private String nameClaim;
    private String emailClaim;
    private String allowedTenants;
    private Integer sortOrder;
    private Date gmtCreate;
    private Date gmtModified;
}
