package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.federation.PlatformAuthConfigService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.user.dto.LoginRequest;
import com.aliyun.autowonder.user.dto.LoginResponse;
import com.aliyun.autowonder.user.dto.LogoutRequest;
import com.aliyun.autowonder.user.dto.RefreshRequest;
import com.aliyun.autowonder.user.dto.RefreshResponse;
import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.RegisterResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final PlatformAuthConfigService authConfigService;

    public AuthController(UserService userService, PlatformAuthConfigService authConfigService) {
        this.userService = userService;
        this.authConfigService = authConfigService;
    }

    /**
     * 公开注册受平台开关约束：关闭后前端隐藏入口，接口也必须拒绝，
     * 不能只靠隐藏按钮。SSO 自动建号走独立服务，不受该开关影响。
     */
    @PostMapping("/register")
    public Result<RegisterResponse> register(@RequestBody RegisterRequest req) {
        if (!authConfigService.registrationEnabled()) {
            throw new BizException(ErrorCode.USER_REGISTRATION_DISABLED);
        }
        return Result.ok(userService.register(req));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req) {
        return Result.ok(userService.login(req));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody LogoutRequest req) {
        userService.logout(req);
        return Result.ok(null);
    }

    @PostMapping("/refresh")
    public Result<RefreshResponse> refresh(@RequestBody RefreshRequest req) {
        String accessToken = userService.refreshAccessToken(
                req.getRefreshToken(), req.getWorkspaceId(), req.isUserActivity());
        return Result.ok(new RefreshResponse(accessToken));
    }
}
