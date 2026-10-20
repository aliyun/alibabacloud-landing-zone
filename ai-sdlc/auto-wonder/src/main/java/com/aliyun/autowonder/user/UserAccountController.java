package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.user.dto.ChangePasswordRequest;
import com.aliyun.autowonder.user.dto.DeactivationRequest;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserProfileVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class UserAccountController {
    private final UserService userService;
    private final UserDeletionService userDeletionService;

    public UserAccountController(UserService userService,
                                 UserDeletionService userDeletionService) {
        this.userService = userService;
        this.userDeletionService = userDeletionService;
    }

    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile() {
        return Result.ok(userService.getProfile(AutoWonderContext.get().getUserId()));
    }

    @PutMapping("/profile")
    public Result<UserProfileVO> updateProfile(@RequestBody UpdateProfileRequest req) {
        return Result.ok(userService.updateProfile(AutoWonderContext.get().getUserId(), req));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        userService.changePassword(AutoWonderContext.get().getUserId(), req);
        return Result.ok(null);
    }

    /**
     * 自助注销 = 立即逻辑删除（与平台用户管理的「删除用户」同一实现、同一校验、同一确认方式）；
     * 本人即操作人，故 adminInitiated = false。恢复由平台管理员在用户管理中执行。
     */
    @PostMapping("/deactivation")
    public Result<Void> initiateDeactivation(@RequestBody DeactivationRequest req) {
        Long userId = AutoWonderContext.get().getUserId();
        userDeletionService.delete(userId, userId, req == null ? null : req.getConfirmUsername(), false);
        return Result.ok(null);
    }
}
