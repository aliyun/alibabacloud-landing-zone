package com.aliyun.autowonder.auth.jwt;

public class TokenPayload {
    private Long userId;
    private Long currentOrgId;
    private String jti;

    public TokenPayload() {}

    public TokenPayload(Long userId, Long currentOrgId, String jti) {
        this.userId = userId;
        this.currentOrgId = currentOrgId;
        this.jti = jti;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCurrentOrgId() { return currentOrgId; }
    public void setCurrentOrgId(Long currentOrgId) { this.currentOrgId = currentOrgId; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
}
