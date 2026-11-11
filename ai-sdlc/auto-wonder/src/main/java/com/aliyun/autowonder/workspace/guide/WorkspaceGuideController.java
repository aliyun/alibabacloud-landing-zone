package com.aliyun.autowonder.workspace.guide;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.guide.dto.SkipWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.UpdateWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.WorkspaceGuideVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前工作空间的新空间初始化引导。工作空间与用户都取登录上下文，不接受请求参数传入，
 * 因此无法读取或推进其他空间的引导。
 */
@RestController
@RequestMapping("/api/workspace-guide")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
        action = "查看新空间初始化引导", actionKey = "access.action.view_workspace_guide")
public class WorkspaceGuideController {

    private final WorkspaceGuideService guideService;

    public WorkspaceGuideController(WorkspaceGuideService guideService) {
        this.guideService = guideService;
    }

    @GetMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
            action = "查看新空间初始化引导", actionKey = "access.action.view_workspace_guide")
    public Result<WorkspaceGuideVO> get() {
        return Result.ok(guideService.get(currentWorkspaceId(), currentUserId()));
    }

    @PutMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "更新新空间初始化引导进度", actionKey = "access.action.update_workspace_guide")
    public Result<WorkspaceGuideVO> advance(@RequestBody UpdateWorkspaceGuideRequest req) {
        return Result.ok(guideService.advance(currentWorkspaceId(), currentUserId(), req));
    }

    @PostMapping("/skip")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "跳过新空间初始化引导", actionKey = "access.action.skip_workspace_guide")
    public Result<WorkspaceGuideVO> skip(@RequestBody(required = false) SkipWorkspaceGuideRequest req) {
        return Result.ok(guideService.skip(currentWorkspaceId(), currentUserId(), req));
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return workspaceId;
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
