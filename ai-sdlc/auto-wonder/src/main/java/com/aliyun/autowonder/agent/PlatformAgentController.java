package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.agent.dto.PlatformAgentStatusVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform-agent")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看平台智能体状态")
public class PlatformAgentController {

    private final PlatformAgentStatusService statusService;

    public PlatformAgentController(PlatformAgentStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public Result<PlatformAgentStatusVO> status() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return Result.ok(statusService.getStatus(workspaceId));
    }
}
