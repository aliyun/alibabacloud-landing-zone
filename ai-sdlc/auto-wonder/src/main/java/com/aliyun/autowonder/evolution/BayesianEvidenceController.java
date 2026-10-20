package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/evidence")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看贝叶斯证据", actionKey = "access.action.view_bayesian_evidence")
public class BayesianEvidenceController {

    private final BayesianEvidenceLiteService evidenceService;
    private final EvidenceLedgerLiteService ledgerService;

    public BayesianEvidenceController(BayesianEvidenceLiteService evidenceService,
                                      EvidenceLedgerLiteService ledgerService) {
        this.evidenceService = evidenceService;
        this.ledgerService = ledgerService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录贝叶斯证据", actionKey = "access.action.record_bayesian_evidence")
    public Result<BayesianEvidenceDO> record(@RequestBody BayesianEvidenceCommand req) {
        return Result.ok(evidenceService.record(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/events")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录演进证据事件", actionKey = "access.action.record_an_evolution_evidence_event")
    public Result<BayesianEvidenceDO> recordEvent(@RequestBody EvidenceLedgerEventCommand req) {
        return Result.ok(ledgerService.recordEvent(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/trigger-check")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "检查演进触发条件", actionKey = "access.action.check_evolution_trigger_conditions")
    public Result<BayesianTriggerDecision> triggerCheck(@RequestBody BayesianTriggerCheckRequest req) {
        return Result.ok(evidenceService.checkTrigger(currentWorkspaceId(), req));
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
