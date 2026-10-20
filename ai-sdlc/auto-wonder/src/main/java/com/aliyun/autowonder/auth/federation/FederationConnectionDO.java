package com.aliyun.autowonder.auth.federation;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 协议连接（providerId）：一条 IdP 实例与一种协议的组合，是登录页入口、回调地址与
 * 身份绑定归属的最小配置单元。不同协议的特有字段共存于一条记录，按 protocol 取用。
 */
@Getter
@Setter
public class FederationConnectionDO {
    private Long id;
    private Long idpId;
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
    private String clientSecret;
    private String scopes;
    /** 稳定 subject 的取值方式：OIDC 支持 "sub"、"tid:oid"（Entra）等 claim 组合；SAML 为属性名，"nameid" 表示 NameID。 */
    private String subjectClaim;
    private String nameClaim;
    private String emailClaim;
    /** 逗号分隔的允许租户（Entra tid）清单，为空表示不限制。 */
    private String allowedTenants;
    private Integer sortOrder;
    private Date gmtCreate;
    private Date gmtModified;
}
