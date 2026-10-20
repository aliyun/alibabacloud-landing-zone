package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform-agent/setup")
@RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_ONLY, action="查看平台智能体状态", actionKey="access.action.view_platform_agent_status")
public class ChiefOfStaffSetupController {
    private final ChiefOfStaffSetupService service;
    public ChiefOfStaffSetupController(ChiefOfStaffSetupService service) { this.service=service; }
    @GetMapping
    public Result<ChiefOfStaffSetupService.Status> inspect() {
        return Result.ok(service.inspect(AutoWonderContext.get().getCurrentWorkspaceId()));
    }
    @PostMapping
    @RequireWorkspaceAccess(value=WorkspaceAccessLevel.ADMIN, action="配置 Chief of Staff", actionKey="access.action.configure_chief_of_staff")
    public Result<ChiefOfStaffSetupService.Status> complete(@RequestBody CreateExecutorRequest request) {
        return Result.ok(service.complete(AutoWonderContext.get().getCurrentWorkspaceId(),
                AutoWonderContext.get().getUserId(), request));
    }
}
