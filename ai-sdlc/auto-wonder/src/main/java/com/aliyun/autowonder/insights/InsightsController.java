package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.aiusage.dto.DispatchAiUsageBackfillResult;
import com.aliyun.autowonder.insights.dto.HumanAgentParticipationVO;
import com.aliyun.autowonder.insights.dto.HumanAgentSlowTailPageVO;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/insights")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看洞察")
public class InsightsController {

    private final InsightsService insightsService;

    public InsightsController(InsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/metrics")
    public Result<InsightMetricsVO> getMetrics(
            @RequestParam(value = "worker_id", required = false) Long workerId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(insightsService.getMetrics(currentWorkspaceId(), workerId, timeRange));
    }

    @GetMapping("/audit")
    public Result<InsightAuditPageVO> getAudit(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(value = "risk_level", required = false) String riskLevel,
            @RequestParam(value = "worker_id", required = false) Long workerId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(insightsService.getAudit(currentWorkspaceId(), riskLevel, workerId, timeRange, page, pageSize));
    }

    @GetMapping("/workers")
    public Result<List<InsightWorkerVO>> getWorkers() {
        return Result.ok(insightsService.getWorkers(currentWorkspaceId()));
    }

    @PostMapping("/usage/backfill")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "回填AI用量数据")
    public Result<DispatchAiUsageBackfillResult> backfillUsage() {
        return Result.ok(insightsService.backfillUsage(currentWorkspaceId()));
    }

    @GetMapping("/human-agent-participation")
    public Result<HumanAgentParticipationVO> getParticipation(
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "granularity", defaultValue = "DAY") String granularity) {
        return Result.ok(insightsService.getParticipation(
                currentWorkspaceId(),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                granularity));
    }

    @GetMapping("/human-agent-participation/slowest")
    public Result<HumanAgentSlowTailPageVO> getSlowTail(
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        int cappedPageSize = Math.min(pageSize, 100);
        return Result.ok(insightsService.getSlowTail(
                currentWorkspaceId(),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                page, cappedPageSize));
    }

    @PostMapping("/human-agent-participation/refresh")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "强制刷新人机协作数据")
    public Result<Void> forceRefreshParticipation() {
        boolean accepted = insightsService.forceParticipationRefresh(currentWorkspaceId());
        if (!accepted) {
            return Result.fail(ErrorCode.SYSTEM_ERROR);
        }
        return Result.ok(null);
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
