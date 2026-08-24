package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.dto.*;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/memories")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看记忆")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建记忆")
    public Result<MemoryVO> create(@RequestBody CreateMemoryRequest req) {
        return Result.ok(memoryService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping
    public Result<List<MemoryVO>> list(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "ownerRef", required = false) Long ownerRef,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(memoryService.list(currentWorkspaceId(), scope, ownerRef, type, status, page, size));
    }

    @GetMapping("/grouped")
    public Result<List<MemoryGroupVO>> listGrouped(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "ownerRef", required = false) Long ownerRef,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.ok(memoryService.listGrouped(currentWorkspaceId(), scope, ownerRef, type, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<MemoryVO> get(@PathVariable("id") Long id) {
        return Result.ok(memoryService.get(id));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新记忆")
    public Result<MemoryVO> update(@PathVariable("id") Long id, @RequestBody UpdateMemoryRequest req) {
        return Result.ok(memoryService.update(id, req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除记忆")
    public Result<Void> delete(@PathVariable("id") Long id) {
        memoryService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/review")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "审核记忆")
    public Result<Void> review(@PathVariable("id") Long id, @RequestBody ReviewRequest req) {
        memoryService.review(id, req, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/reviews")
    public Result<List<MemoryVO>> pendingReviews(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(memoryService.list(currentWorkspaceId(), null, null, null, "PENDING", page, size));
    }

    @GetMapping("/reviews/count")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "查看待审核记忆数量")
    public Result<Long> countPendingReviews() {
        return Result.ok(memoryService.countPendingReviews(currentWorkspaceId()));
    }

    @PostMapping("/from-artifact")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "从产物导入记忆")
    public Result<MemoryVO> importFromArtifact(@RequestBody ImportFromArtifactRequest req) {
        return Result.ok(memoryService.importFromArtifact(req, currentWorkspaceId(), currentUserId()));
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
