package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResponse {
    private Long userId;
    private String accessToken;
    private String refreshToken;
    private UserVO user;

    public LoginResponse(Long userId, String accessToken, String refreshToken, UserVO user) {
        this.userId = userId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.user = user;
    }
}
