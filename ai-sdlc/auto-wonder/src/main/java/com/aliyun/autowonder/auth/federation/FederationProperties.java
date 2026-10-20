package com.aliyun.autowonder.auth.federation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autowonder.federation")
public class FederationProperties {
    /** SP Entity ID：SAML AuthnRequest 的 Issuer，也是断言 Audience 必须包含的值。 */
    private String spEntityId = "autowonder";
    /** 登录发起状态（state/RelayState）有效期。 */
    private int loginStateTtlSeconds = 600;
    /** 回调后一次性票据有效期。 */
    private int ticketTtlSeconds = 300;
    /** 断言时间窗口允许的时钟偏移。 */
    private int clockSkewSeconds = 120;
    /** IdP JWKS 内存缓存时长。 */
    private int jwksCacheTtlSeconds = 3600;
    /** 访问 IdP token/JWKS 端点的超时。 */
    private int httpTimeoutSeconds = 15;

    public String getSpEntityId() { return spEntityId; }
    public void setSpEntityId(String v) { this.spEntityId = v; }
    public int getLoginStateTtlSeconds() { return loginStateTtlSeconds; }
    public void setLoginStateTtlSeconds(int v) { this.loginStateTtlSeconds = v; }
    public int getTicketTtlSeconds() { return ticketTtlSeconds; }
    public void setTicketTtlSeconds(int v) { this.ticketTtlSeconds = v; }
    public int getClockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(int v) { this.clockSkewSeconds = v; }
    public int getJwksCacheTtlSeconds() { return jwksCacheTtlSeconds; }
    public void setJwksCacheTtlSeconds(int v) { this.jwksCacheTtlSeconds = v; }
    public int getHttpTimeoutSeconds() { return httpTimeoutSeconds; }
    public void setHttpTimeoutSeconds(int v) { this.httpTimeoutSeconds = v; }
}
