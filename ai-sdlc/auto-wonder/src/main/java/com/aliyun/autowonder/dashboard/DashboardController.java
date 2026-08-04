package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dashboard.dto.CompletedWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看仪表盘")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/realtime")
    public Result<RealtimeDashboardVO> getRealtime() {
        return Result.ok(dashboardService.getRealtime(currentOrgId()));
    }

    @GetMapping("/agents/{id}/running")
    public Result<List<RunningTaskVO>> getAgentRunning(@PathVariable("id") long agentId) {
        return Result.ok(dashboardService.getAgentRunning(currentOrgId(), agentId));
    }

    @GetMapping("/completed/today")
    public Result<List<CompletedWorkitemVO>> getTodayCompleted() {
        return Result.ok(dashboardService.getTodayCompleted(currentOrgId()));
    }

    @GetMapping("/completed/week")
    public Result<List<CompletedWorkitemVO>> getWeekCompleted() {
        return Result.ok(dashboardService.getWeekCompleted(currentOrgId()));
    }

    @GetMapping("/running")
    public Result<List<RunningTaskVO>> getRunning() {
        return Result.ok(dashboardService.getRunningWorkitems(currentOrgId()));
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
