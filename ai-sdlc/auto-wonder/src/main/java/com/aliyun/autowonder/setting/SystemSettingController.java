package com.aliyun.autowonder.setting;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.setting.dto.SettingVO;
import com.aliyun.autowonder.setting.dto.UpdateSettingsRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
@RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "管理系统设置")
public class SystemSettingController {

    private final SystemSettingService settingService;

    public SystemSettingController(SystemSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping("/{group}")
    public Result<List<SettingVO>> listByGroup(@PathVariable("group") String group) {
        return Result.ok(settingService.listByGroup(group, currentOrgId()));
    }

    @PutMapping("/{group}")
    public Result<Void> updateGroup(@PathVariable("group") String group,
                                     @RequestBody UpdateSettingsRequest req) {
        settingService.updateGroup(group, req, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) { throw new BizException(ErrorCode.UNAUTHORIZED); }
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) { throw new BizException(ErrorCode.ORG_NOT_MEMBER); }
        return orgId;
    }
}
