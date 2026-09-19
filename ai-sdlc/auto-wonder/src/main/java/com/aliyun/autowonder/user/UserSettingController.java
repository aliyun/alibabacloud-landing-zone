package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.user.dto.UpsertUserSettingRequest;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前登录用户的偏好配置。刻意不加 {@code @RequireWorkspaceAccess}：偏好归属用户本人、
 * 与所在工作空间无关，加了会让同一用户在不同工作空间读到不同的发送方式。
 * userId 只从登录上下文取，不接受请求参数传入，因此无法越权读写他人配置。
 */
@RestController
@RequestMapping("/api/users/me/settings")
public class UserSettingController {

    private final UserSettingService userSettingService;

    public UserSettingController(UserSettingService userSettingService) {
        this.userSettingService = userSettingService;
    }

    @GetMapping
    public Result<List<UserSettingVO>> list() {
        return Result.ok(userSettingService.listByUser(currentUserId()));
    }

    @GetMapping("/{key}")
    public Result<UserSettingVO> get(@PathVariable("key") String key) {
        return Result.ok(userSettingService.get(currentUserId(), key));
    }

    @PutMapping("/{key}")
    public Result<UserSettingVO> upsert(@PathVariable("key") String key,
                                        @RequestBody UpsertUserSettingRequest req) {
        String valueJson = req == null ? null : req.getValueJson();
        return Result.ok(userSettingService.upsert(currentUserId(), key, valueJson));
    }

    @DeleteMapping("/{key}")
    public Result<Void> delete(@PathVariable("key") String key) {
        userSettingService.delete(currentUserId(), key);
        return Result.ok(null);
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
