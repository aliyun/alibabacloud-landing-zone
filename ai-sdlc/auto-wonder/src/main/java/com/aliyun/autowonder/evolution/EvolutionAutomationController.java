package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进自动化")
public class EvolutionAutomationController {

    private final EvolutionOrchestratorLiteService orchestratorService;
    private final EvolutionReplayExecutorLiteService replayExecutorService;
    private final EvolutionCanaryPostprocessLiteService canaryPostprocessService;

    public EvolutionAutomationController(EvolutionOrchestratorLiteService orchestratorService,
                                         EvolutionReplayExecutorLiteService replayExecutorService,
                                         EvolutionCanaryPostprocessLiteService canaryPostprocessService) {
        this.orchestratorService = orchestratorService;
        this.replayExecutorService = replayExecutorService;
        this.canaryPostprocessService = canaryPostprocessService;
    }

    @PostMapping("/orchestrate")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "编排演进自动化")
    public Result<EvolutionOrchestrateResult> orchestrate(@RequestBody EvolutionOrchestrateCommand req) {
        return Result.ok(orchestratorService.orchestrate(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/replay/execute")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "执行演进回放")
    public Result<EvolutionReplayExecuteResult> executeReplay(@RequestBody EvolutionReplayExecuteCommand req) {
        return Result.ok(replayExecutorService.execute(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/canary/postprocess")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "处理演进灰度结果")
    public Result<EvolutionCanaryPostprocessResult> postprocessCanary(
            @RequestBody EvolutionCanaryPostprocessCommand req) {
        return Result.ok(canaryPostprocessService.postprocess(req, currentWorkspaceId(), currentUserId()));
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
