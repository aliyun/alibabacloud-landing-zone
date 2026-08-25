package com.aliyun.autowonder.auth.jwt;

public class TokenPayload {
    private Long userId;
    private Long currentWorkspaceId;
    private String jti;

    public TokenPayload() {}

    public TokenPayload(Long userId, Long currentWorkspaceId, String jti) {
        this.userId = userId;
        this.currentWorkspaceId = currentWorkspaceId;
        this.jti = jti;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCurrentWorkspaceId() { return currentWorkspaceId; }
    public void setCurrentWorkspaceId(Long currentWorkspaceId) { this.currentWorkspaceId = currentWorkspaceId; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
}
