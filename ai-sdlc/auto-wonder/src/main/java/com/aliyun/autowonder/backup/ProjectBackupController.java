package com.aliyun.autowonder.backup;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces/current/backups")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理项目配置备份")
public class ProjectBackupController {
    private final ProjectBackupService service;
    public ProjectBackupController(ProjectBackupService service) { this.service = service; }

    @PostMapping
    public Result<ProjectBackupService.Backup> create() {
        long workspaceId = workspaceId();
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return Result.ok(service.create(workspaceId, userId));
    }

    @GetMapping
    public Result<List<ProjectBackupService.Backup>> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.list(workspaceId(), Math.max(1, page), Math.max(1, Math.min(100, size))));
    }

    @GetMapping("/{id}/download")
    public Result<Map<String, String>> download(@PathVariable String id) {
        return Result.ok(Map.of("url", service.download(workspaceId(), id)));
    }

    private long workspaceId() {
        Long id = AutoWonderContext.get().getCurrentWorkspaceId();
        if (id == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return id;
    }
}
