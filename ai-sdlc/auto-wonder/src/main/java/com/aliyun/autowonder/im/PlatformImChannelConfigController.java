package com.aliyun.autowonder.im;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.PlatformImChannelConfigVO;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/platform/im-channels")
public class PlatformImChannelConfigController {
    private final PlatformImChannelConfigService configService;
    private final SystemAdminService systemAdminService;

    public PlatformImChannelConfigController(
            PlatformImChannelConfigService configService,
            SystemAdminService systemAdminService) {
        this.configService = configService;
        this.systemAdminService = systemAdminService;
    }

    @GetMapping
    public Result<List<PlatformImChannelConfigVO>> list() {
        return Result.ok(configService.list(AutoWonderContext.get().getUserId()));
    }

    @PutMapping("/dingtalk")
    public Result<PlatformImChannelConfigVO> updateDingTalk(
            @Valid @RequestBody UpdateDingTalkChannelRequest request) {
        systemAdminService.requireFirstActiveUser(
                AutoWonderContext.get().getUserId(), "管理协作通知");
        return Result.ok(configService.updateDingTalk(
                AutoWonderContext.get().getUserId(), request));
    }
}
