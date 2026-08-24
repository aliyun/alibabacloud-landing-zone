package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/gates")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进门禁运行")
public class EvolutionGateRunController {

    private final EvolutionGateRunLiteService gateRunService;

    public EvolutionGateRunController(EvolutionGateRunLiteService gateRunService) {
        this.gateRunService = gateRunService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录演进门禁运行")
    public Result<EvolutionGateRunDO> record(@RequestBody EvolutionGateRunCommand req) {
        return Result.ok(gateRunService.record(req, currentWorkspaceId(), currentUserId()));
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
