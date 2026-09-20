package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform-intelligence")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看平台智能能力状态")
public class PlatformIntelligenceController {
    private final PlatformIntelligenceService service;

    public PlatformIntelligenceController(PlatformIntelligenceService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Result<PlatformIntelligenceService.CapabilityStatus> status() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Result.ok(service.getStatus(workspaceId));
    }
}
