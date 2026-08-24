package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dashboard.dto.CompletedWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看仪表盘")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/realtime")
    public Result<RealtimeDashboardVO> getRealtime() {
        return Result.ok(dashboardService.getRealtime(currentWorkspaceId()));
    }

    @GetMapping("/agents/{id}/running")
    public Result<List<RunningTaskVO>> getAgentRunning(@PathVariable("id") long agentId) {
        return Result.ok(dashboardService.getAgentRunning(currentWorkspaceId(), agentId));
    }

    @GetMapping("/completed/today")
    public Result<List<CompletedWorkitemVO>> getTodayCompleted() {
        return Result.ok(dashboardService.getTodayCompleted(currentWorkspaceId()));
    }

    @GetMapping("/completed/week")
    public Result<List<CompletedWorkitemVO>> getWeekCompleted() {
        return Result.ok(dashboardService.getWeekCompleted(currentWorkspaceId()));
    }

    @GetMapping("/running")
    public Result<List<RunningTaskVO>> getRunning() {
        return Result.ok(dashboardService.getRunningWorkitems(currentWorkspaceId()));
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
