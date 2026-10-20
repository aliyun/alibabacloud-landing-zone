package com.aliyun.autowonder.auth.federation;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 回调完成后下发的一次性票据：LOGIN 票据携带已绑定的 AW 用户，用于换取会话；
 * BINDING 票据携带已验证的外部身份，用于在受保护流程中完成首次绑定。
 */
@Getter
@Setter
public class FederationTicket implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Purpose { LOGIN, BINDING }

    private Purpose purpose;
    private Long providerId;
    private String providerName;
    private String issuer;
    private String subject;
    private String displayName;
    private String email;
    private Long userId;
}
