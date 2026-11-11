package com.aliyun.autowonder.auth.federation;

/**
 * 协议适配器输出的统一外部身份：只包含已通过协议校验的稳定标识与展示信息，
 * 不携带任何 AutoWonder 用户、会话或工作空间语义。
 * feishuUserId 仅在连接配置了飞书属性名且认证结果携带该属性时有值。
 */
public record ExternalIdentity(Long providerId, String issuer, String subject,
                               String displayName, String email, String feishuUserId) {

    public ExternalIdentity(Long providerId, String issuer, String subject,
                            String displayName, String email) {
        this(providerId, issuer, subject, displayName, email, null);
    }
}
