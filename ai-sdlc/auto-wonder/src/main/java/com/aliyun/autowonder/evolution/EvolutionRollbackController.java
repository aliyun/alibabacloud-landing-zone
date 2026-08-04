package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/rollback")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看演进回滚")
public class EvolutionRollbackController {

    private final EvolutionReleaseRollbackLiteService rollbackService;

    public EvolutionRollbackController(EvolutionReleaseRollbackLiteService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @PostMapping("/{proposalId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "回滚演进变更")
    public Result<EvolutionRollbackResult> rollback(@PathVariable("proposalId") Long proposalId) {
        return Result.ok(rollbackService.rollback(proposalId, currentOrgId(), currentUserId()));
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
