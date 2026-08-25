package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.AiQuotaVO;
import com.aliyun.autowonder.aiusage.dto.AiUsageVO;
import com.aliyun.autowonder.aiusage.dto.UpdateQuotaRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai-usage")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看AI用量")
public class AiUsageController {

    private final AiUsageService aiUsageService;

    public AiUsageController(AiUsageService aiUsageService) {
        this.aiUsageService = aiUsageService;
    }

    @GetMapping
    public Result<List<AiUsageVO>> listUsage(
            @RequestParam(value = "period", required = false) String period) {
        return Result.ok(aiUsageService.listUsage(currentWorkspaceId(), period));
    }

    @GetMapping("/quota")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "查看AI用量配额")
    public Result<AiQuotaVO> getQuota() {
        return Result.ok(aiUsageService.getQuota(currentWorkspaceId()));
    }

    @PutMapping("/quota")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新AI用量配额")
    public Result<Void> updateQuota(@RequestBody UpdateQuotaRequest req) {
        aiUsageService.updateQuota(req, currentWorkspaceId());
        return Result.ok(null);
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) { throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER); }
        return workspaceId;
    }
}
