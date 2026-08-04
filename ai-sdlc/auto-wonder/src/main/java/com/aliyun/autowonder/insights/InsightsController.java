package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.aiusage.dto.DispatchAiUsageBackfillResult;
import com.aliyun.autowonder.insights.dto.InsightAuditPageVO;
import com.aliyun.autowonder.insights.dto.InsightMetricsVO;
import com.aliyun.autowonder.insights.dto.InsightWorkerVO;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/insights")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看洞察")
public class InsightsController {

    private final InsightsService insightsService;

    public InsightsController(InsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/metrics")
    public Result<InsightMetricsVO> getMetrics(
            @RequestParam(value = "worker_id", required = false) Long workerId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(insightsService.getMetrics(currentOrgId(), workerId, timeRange));
    }

    @GetMapping("/audit")
    public Result<InsightAuditPageVO> getAudit(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(value = "risk_level", required = false) String riskLevel,
            @RequestParam(value = "worker_id", required = false) Long workerId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(insightsService.getAudit(currentOrgId(), riskLevel, workerId, timeRange, page, pageSize));
    }

    @GetMapping("/workers")
    public Result<List<InsightWorkerVO>> getWorkers() {
        return Result.ok(insightsService.getWorkers(currentOrgId()));
    }

    @PostMapping("/usage/backfill")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "回填AI用量数据")
    public Result<DispatchAiUsageBackfillResult> backfillUsage() {
        return Result.ok(insightsService.backfillUsage(currentOrgId()));
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
