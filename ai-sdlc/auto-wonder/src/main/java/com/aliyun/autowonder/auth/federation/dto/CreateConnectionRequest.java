package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 新建协议连接。idpId + protocol 决定该连接的语义（哪套协议参数生效），
 * 二者在连接建立后不可修改。
 */
@Getter
@Setter
public class CreateConnectionRequest {
    private Long idpId;
    private String name;
    /** SAML2 或 OIDC。 */
    private String protocol;
    /** null 默认启用。 */
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
