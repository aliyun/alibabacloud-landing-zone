package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.user.dto.ChangePasswordRequest;
import com.aliyun.autowonder.user.dto.DeactivationRequest;
import com.aliyun.autowonder.user.dto.DeactivationStatusVO;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserProfileVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class UserAccountController {
    private final UserService userService;
    private final AccountDeactivationService deactivationService;

    public UserAccountController(UserService userService,
                                 AccountDeactivationService deactivationService) {
        this.userService = userService;
        this.deactivationService = deactivationService;
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

    @PostMapping("/deactivation")
    public Result<Void> initiateDeactivation(@RequestBody DeactivationRequest req) {
        deactivationService.initiateDeactivation(AutoWonderContext.get().getUserId(), req);
        return Result.ok(null);
    }

    @PostMapping("/deactivation/revoke")
    public Result<Void> revokeDeactivation() {
        deactivationService.revokeDeactivation(AutoWonderContext.get().getUserId());
        return Result.ok(null);
    }

    @GetMapping("/deactivation")
    public Result<DeactivationStatusVO> getDeactivationStatus() {
        DeactivationStatusVO status = deactivationService.getDeactivationStatus(
                AutoWonderContext.get().getUserId());
        return Result.ok(status);
    }
}
