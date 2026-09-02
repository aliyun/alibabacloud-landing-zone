package com.aliyun.autowonder.scheduledtask.compat;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capabilities/scheduled-task")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看定时任务能力")
public class ScheduledTaskCapabilityController {

    private final ScheduledTaskCapabilityGuard guard;

    public ScheduledTaskCapabilityController(ScheduledTaskCapabilityGuard guard) {
        this.guard = guard;
    }

    @GetMapping
    public Result<ScheduledTaskCapabilityVO> get() {
        return Result.ok(guard.snapshot());
    }
}
