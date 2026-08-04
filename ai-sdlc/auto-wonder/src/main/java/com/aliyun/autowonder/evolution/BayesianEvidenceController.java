package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/evidence")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看贝叶斯证据")
public class BayesianEvidenceController {

    private final BayesianEvidenceLiteService evidenceService;
    private final EvidenceLedgerLiteService ledgerService;

    public BayesianEvidenceController(BayesianEvidenceLiteService evidenceService,
                                      EvidenceLedgerLiteService ledgerService) {
        this.evidenceService = evidenceService;
        this.ledgerService = ledgerService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "记录贝叶斯证据")
    public Result<BayesianEvidenceDO> record(@RequestBody BayesianEvidenceCommand req) {
        return Result.ok(evidenceService.record(req, currentOrgId(), currentUserId()));
    }

    @PostMapping("/events")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "记录演进证据事件")
    public Result<BayesianEvidenceDO> recordEvent(@RequestBody EvidenceLedgerEventCommand req) {
        return Result.ok(ledgerService.recordEvent(req, currentOrgId(), currentUserId()));
    }

    @PostMapping("/trigger-check")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "检查演进触发条件")
    public Result<BayesianTriggerDecision> triggerCheck(@RequestBody BayesianTriggerCheckRequest req) {
        return Result.ok(evidenceService.checkTrigger(currentOrgId(), req));
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
