package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dashboard.dto.CompletedWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.OverviewVO;
import com.aliyun.autowonder.dashboard.dto.PipelineHealthVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.dashboard.dto.RunningWorkitemVO;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看仪表盘", actionKey = "access.action.view_the_dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/realtime")
    public Result<RealtimeDashboardVO> getRealtime(
            @RequestParam(value = "range", required = false) Integer range,
            @RequestParam(value = "agentId", required = false) String agentId) {
        return Result.ok(dashboardService.getRealtime(currentWorkspaceId(), range, agentId));
    }

    @GetMapping("/overview")
    public Result<OverviewVO> getOverview() {
        return Result.ok(dashboardService.getOverview(currentWorkspaceId()));
    }

    @GetMapping("/pipeline-health")
    public Result<PipelineHealthVO> getPipelineHealth(@RequestParam(value = "range", required = false) String range) {
        return Result.ok(dashboardService.getPipelineHealth(currentWorkspaceId(), range));
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
    public Result<List<RunningWorkitemVO>> getRunning() {
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
