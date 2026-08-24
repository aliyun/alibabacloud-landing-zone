package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.repo.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看代码仓库")
public class RepoController {

    private final RepoService repoService;

    public RepoController(RepoService repoService) {
        this.repoService = repoService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建代码仓库")
    public Result<RepoVO> create(@RequestBody CreateRepoRequest req) {
        return Result.ok(repoService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/test-connection")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "测试代码仓库连接")
    public Result<RepoConnectionTestResult> testConnection(@RequestBody TestRepoConnectionRequest req) {
        return Result.ok(repoService.testConnection(req));
    }

    @GetMapping
    public Result<List<RepoVO>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(repoService.list(currentWorkspaceId(), page, size));
    }

    @GetMapping("/{id}")
    public Result<RepoVO> get(@PathVariable("id") Long id) {
        return Result.ok(repoService.get(id));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新代码仓库")
    public Result<RepoVO> update(@PathVariable("id") Long id, @RequestBody UpdateRepoRequest req) {
        return Result.ok(repoService.update(id, req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除代码仓库")
    public Result<Void> delete(@PathVariable("id") Long id) {
        repoService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/scan")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "扫描代码仓库")
    public Result<Void> startScan(@PathVariable("id") Long id) {
        repoService.startScan(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/{id}/conclusion")
    public Result<RepoConclusionVO> getConclusion(@PathVariable("id") Long id) {
        return Result.ok(repoService.getConclusion(id));
    }

    @PutMapping("/{id}/conclusion")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新代码仓库结论")
    public Result<RepoConclusionVO> updateConclusion(@PathVariable("id") Long id,
                                                      @RequestBody UpdateConclusionRequest req) {
        return Result.ok(repoService.updateConclusion(id, req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/relations")
    public Result<List<RepoRelationVO>> listRelations(
            @RequestParam(value = "repoId", required = false) Long repoId) {
        if (repoId != null) {
            return Result.ok(repoService.listRelationsByRepoId(currentWorkspaceId(), repoId));
        }
        return Result.ok(repoService.listRelations(currentWorkspaceId()));
    }

    @PostMapping("/relations")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建代码仓库关联")
    public Result<RepoRelationVO> createRelation(@RequestBody CreateRelationRequest req) {
        return Result.ok(repoService.createRelation(req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/relations/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除代码仓库关联")
    public Result<Void> deleteRelation(@PathVariable("id") Long id) {
        repoService.deleteRelation(id, currentWorkspaceId());
        return Result.ok(null);
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
