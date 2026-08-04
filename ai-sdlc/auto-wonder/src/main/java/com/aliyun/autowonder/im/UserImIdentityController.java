package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/users/me/im-identities")
public class UserImIdentityController {
    private final UserImIdentityService identityService;

    public UserImIdentityController(UserImIdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping
    public Result<List<UserImIdentityVO>> list() {
        return Result.ok(identityService.list(AutoWonderContext.get().getUserId()));
    }

    @PutMapping("/dingtalk")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "配置个人钉钉身份")
    public Result<UserImIdentityVO> updateDingTalk(
            @Valid @RequestBody UpdateUserImIdentityRequest request) {
        return Result.ok(identityService.update(
                AutoWonderContext.get().getUserId(),
                ImProviderType.DINGTALK.getKey(),
                request.getExternalUserId()));
    }

    @PostMapping("/dingtalk/test")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "测试个人钉钉身份")
    public Result<Void> testDingTalk() {
        identityService.sendTest(
                AutoWonderContext.get().getUserId(),
                ImProviderType.DINGTALK.getKey());
        return Result.ok(null);
    }
}
