package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memory-stores/directory")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看记忆归属")
public class MemoryDirectoryController {
    private final MemoryDirectoryService directory;
    public MemoryDirectoryController(MemoryDirectoryService directory) { this.directory = directory; }

    @GetMapping
    public Result<MemoryDirectoryService.Directory> list() {
        var context = AutoWonderContext.get();
        if (context.getCurrentWorkspaceId() == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        if (context.getUserId() == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return Result.ok(directory.list(context.getCurrentWorkspaceId(), context.getUserId(),
                context.getWorkspaceAccessLevel() == WorkspaceAccessLevel.ADMIN));
    }
}
