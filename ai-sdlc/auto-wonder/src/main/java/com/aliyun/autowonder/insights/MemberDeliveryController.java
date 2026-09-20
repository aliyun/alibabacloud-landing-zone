package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/insights/member-delivery")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看项目成员交付统计")
public class MemberDeliveryController {
    private final MemberDeliveryService service;
    public MemberDeliveryController(MemberDeliveryService service) { this.service = service; }
    @GetMapping
    public Result<MemberDeliveryService.Report> get(@RequestParam(value="start_date", required=false) String start,
                                                    @RequestParam(value="end_date", required=false) String end) {
        Long workspace = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspace == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return Result.ok(service.get(workspace, start, end));
    }
}
