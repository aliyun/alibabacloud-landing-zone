package com.aliyun.autowonder.auth.federation;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 一次登录发起的服务端状态：回调时必须一次性消费，用于请求关联（SAML InResponseTo）、
 * nonce 与 PKCE 校验（OIDC）。受保护的多身份绑定与登录入口共用本状态，靠 purpose 区分，
 * 绑定态额外携带发起绑定的已登录用户，回调时以该用户为目标建立绑定。
 */
@Getter
@Setter
public class FederationLoginState implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Purpose { LOGIN, BINDING }

    private Purpose purpose;
    private Long bindUserId;
    private Long providerId;
    private String requestId;
    private String nonce;
    private String codeVerifier;
    private String redirectUri;
}
