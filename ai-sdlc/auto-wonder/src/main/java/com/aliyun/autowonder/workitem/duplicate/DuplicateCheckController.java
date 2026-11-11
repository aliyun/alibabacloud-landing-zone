package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重复工单预检。只读预检，不创建工单；同名结果同步返回，同意图异步返回句柄。
 */
@RestController
@RequestMapping("/api/workitems/duplicate-checks")
public class DuplicateCheckController {

    private final WorkitemDuplicateService duplicateService;

    public DuplicateCheckController(WorkitemDuplicateService duplicateService) {
        this.duplicateService = duplicateService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
            action = "检查重复工单", actionKey = "access.action.check_duplicate_workitem")
    public Result<DuplicateCheckVO> precheck(@RequestBody DuplicateCheckRequest request) {
        return Result.ok(duplicateService.precheck(currentWorkspaceId(), currentUserId(), request));
    }

    @GetMapping("/{checkId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
            action = "检查重复工单", actionKey = "access.action.check_duplicate_workitem")
    public Result<DuplicateCheckVO> get(@PathVariable("checkId") Long checkId) {
        return Result.ok(duplicateService.getCheck(currentWorkspaceId(), currentUserId(), checkId));
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
