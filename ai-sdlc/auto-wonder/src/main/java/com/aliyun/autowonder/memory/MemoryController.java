package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.dto.*;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/memories")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看记忆")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建记忆")
    public Result<MemoryVO> create(@RequestBody CreateMemoryRequest req) {
        return Result.ok(memoryService.create(req, currentOrgId(), currentUserId()));
    }

    @GetMapping
    public Result<List<MemoryVO>> list(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "ownerRef", required = false) Long ownerRef,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(memoryService.list(currentOrgId(), scope, ownerRef, type, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<MemoryVO> get(@PathVariable("id") Long id) {
        return Result.ok(memoryService.get(id));
    }

    @PutMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新记忆")
    public Result<MemoryVO> update(@PathVariable("id") Long id, @RequestBody UpdateMemoryRequest req) {
        return Result.ok(memoryService.update(id, req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除记忆")
    public Result<Void> delete(@PathVariable("id") Long id) {
        memoryService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/review")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "审核记忆")
    public Result<Void> review(@PathVariable("id") Long id, @RequestBody ReviewRequest req) {
        memoryService.review(id, req, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/reviews")
    public Result<List<MemoryVO>> pendingReviews(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(memoryService.list(currentOrgId(), null, null, null, "PENDING", page, size));
    }

    @GetMapping("/reviews/count")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "查看待审核记忆数量")
    public Result<Long> countPendingReviews() {
        return Result.ok(memoryService.countPendingReviews(currentOrgId()));
    }

    @PostMapping("/from-artifact")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "从产物导入记忆")
    public Result<MemoryVO> importFromArtifact(@RequestBody ImportFromArtifactRequest req) {
        return Result.ok(memoryService.importFromArtifact(req, currentOrgId(), currentUserId()));
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
