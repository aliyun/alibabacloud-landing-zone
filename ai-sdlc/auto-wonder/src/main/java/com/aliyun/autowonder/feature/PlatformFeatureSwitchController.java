package com.aliyun.autowonder.feature;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.feature.dto.FeatureSwitchVO;
import com.aliyun.autowonder.feature.dto.UpdateFeatureSwitchRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台级功能开关。类上刻意不加 {@code @RequireWorkspaceAccess}：配置归平台而非某个空间，
 * 因此不接受 workspaceId，也不随空间切换变化。
 */
@RestController
@RequestMapping("/api/platform/feature-switches")
public class PlatformFeatureSwitchController {

    private final PlatformFeatureSwitchService featureSwitchService;
    private final SystemAdminService systemAdminService;

    public PlatformFeatureSwitchController(PlatformFeatureSwitchService featureSwitchService,
            SystemAdminService systemAdminService) {
        this.featureSwitchService = featureSwitchService;
        this.systemAdminService = systemAdminService;
    }

    @GetMapping
    public Result<FeatureSwitchVO> get() {
        return Result.ok(featureSwitchService.get());
    }

    @PutMapping
    public Result<FeatureSwitchVO> update(@RequestBody UpdateFeatureSwitchRequest request) {
        long userId = currentUserId();
        systemAdminService.requireSystemAdmin(userId, "配置功能开关", "access.admin.feature_switch_denied");
        return Result.ok(featureSwitchService.update(userId, request));
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
