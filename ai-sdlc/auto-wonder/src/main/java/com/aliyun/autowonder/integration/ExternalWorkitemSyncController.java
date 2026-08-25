package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workitems")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看外部工作项同步")
public class ExternalWorkitemSyncController {

    private final ExternalWorkitemSyncService syncService;

    public ExternalWorkitemSyncController(ExternalWorkitemSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/{id}/external-sync")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "同步外部工作项")
    public Result<AoneSyncResult> syncExternal(@PathVariable("id") Long id) {
        return Result.ok(syncService.syncLocalWorkitem(id, currentWorkspaceId(), currentUserId()));
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
