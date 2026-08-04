package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.LoginRequest;
import com.aliyun.autowonder.user.dto.LoginResponse;
import com.aliyun.autowonder.user.dto.LogoutRequest;
import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.UserVO;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {
    private final UserDao userDao;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final JwtProperties jwtProperties;

    public UserService(UserDao userDao, JwtService jwtService,
                       SessionService sessionService, JwtProperties jwtProperties) {
        this.userDao = userDao;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.jwtProperties = jwtProperties;
    }

    public UserVO register(RegisterRequest req) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        if (userDao.findByUsername(req.getUsername()) != null) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        UserDO user = new UserDO();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setNickname(req.getNickname());
        user.setPasswordHash(PasswordEncoderUtil.encode(req.getPassword()));
        user.setStatus(0);
        userDao.insert(user);

        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        return vo;
    }

    public LoginResponse login(LoginRequest req) {
        UserDO user = userDao.findByUsername(req.getUsername());
        if (user == null || user.getPasswordHash() == null
                || !PasswordEncoderUtil.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String jti = UUID.randomUUID().toString();
        String accessToken = jwtService.signAccess(new TokenPayload(user.getId(), null, jti));
        String refreshToken = UUID.randomUUID().toString();
        sessionService.storeRefresh(refreshToken, user.getId(), (int) jwtProperties.getRefreshTtlSeconds());
        return new LoginResponse(user.getId(), accessToken, refreshToken, toVO(user));
    }

    public void logout(LogoutRequest req) {
        if (req != null && req.getRefreshToken() != null) {
            sessionService.revokeRefresh(req.getRefreshToken());
        }
    }

    public String refreshAccessToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "refreshToken 不能为空");
        }
        Long userId = sessionService.getUserIdByRefresh(refreshToken);
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }
        String jti = UUID.randomUUID().toString();
        return jwtService.signAccess(new TokenPayload(userId, null, jti));
    }

    private UserVO toVO(UserDO user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        return vo;
    }
}
