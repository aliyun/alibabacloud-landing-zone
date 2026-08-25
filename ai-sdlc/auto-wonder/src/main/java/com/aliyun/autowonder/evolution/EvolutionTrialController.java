package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/proposals/{id}/trial")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进试验")
public class EvolutionTrialController {

    private final EvolutionHypothesisTrialLiteService trialService;

    public EvolutionTrialController(EvolutionHypothesisTrialLiteService trialService) {
        this.trialService = trialService;
    }

    @PostMapping("/start")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "启动演进试验")
    public Result<EvolutionTrialDecision> start(@PathVariable("id") Long id,
                                                @RequestBody EvolutionTrialStartRequest req) {
        return Result.ok(trialService.startTrial(id, req == null ? null : req.getTaskPatternKey(),
                currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/evidence")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录演进试验结果")
    public Result<EvolutionTrialDecision> recordOutcome(@PathVariable("id") Long id,
                                                        @RequestBody EvolutionTrialEvidenceCommand req) {
        return Result.ok(trialService.recordOutcome(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/decide")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "决策演进试验")
    public Result<EvolutionTrialDecision> decide(@PathVariable("id") Long id) {
        return Result.ok(trialService.decide(id, currentWorkspaceId(), currentUserId()));
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
