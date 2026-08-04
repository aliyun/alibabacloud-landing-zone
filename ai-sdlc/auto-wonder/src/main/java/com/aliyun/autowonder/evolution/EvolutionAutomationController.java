package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看演进自动化")
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
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "编排演进自动化")
    public Result<EvolutionOrchestrateResult> orchestrate(@RequestBody EvolutionOrchestrateCommand req) {
        return Result.ok(orchestratorService.orchestrate(req, currentOrgId(), currentUserId()));
    }

    @PostMapping("/replay/execute")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "执行演进回放")
    public Result<EvolutionReplayExecuteResult> executeReplay(@RequestBody EvolutionReplayExecuteCommand req) {
        return Result.ok(replayExecutorService.execute(req, currentOrgId(), currentUserId()));
    }

    @PostMapping("/canary/postprocess")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "处理演进灰度结果")
    public Result<EvolutionCanaryPostprocessResult> postprocessCanary(
            @RequestBody EvolutionCanaryPostprocessCommand req) {
        return Result.ok(canaryPostprocessService.postprocess(req, currentOrgId(), currentUserId()));
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
