package com.aliyun.autowonder.auth.session;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.dto.LoginResponse;
import com.aliyun.autowonder.user.dto.UserVO;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 共享会话签发：密码登录与联合登录都在身份校验通过后走这里，
 * 保证两种入口签发的 AW 会话（access/refresh 及用户视图）完全一致。
 */
@Service
public class SessionIssuanceService {

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final JwtProperties jwtProperties;

    public SessionIssuanceService(JwtService jwtService, SessionService sessionService,
                                  JwtProperties jwtProperties) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.jwtProperties = jwtProperties;
    }

    public LoginResponse issue(UserDO user) {
        String jti = UUID.randomUUID().toString();
        String accessToken = jwtService.signAccess(new TokenPayload(user.getId(), null, jti));
        String refreshToken = UUID.randomUUID().toString();
        sessionService.storeRefresh(refreshToken, user.getId(), (int) jwtProperties.getRefreshTtlSeconds());
        return new LoginResponse(user.getId(), accessToken, refreshToken, toVO(user));
    }

    private UserVO toVO(UserDO user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setIsAdmin(Integer.valueOf(1).equals(user.getIsAdmin()));
        return vo;
    }
}
