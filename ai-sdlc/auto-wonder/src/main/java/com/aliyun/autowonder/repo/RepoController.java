package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.repo.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看代码仓库")
public class RepoController {

    private final RepoService repoService;

    public RepoController(RepoService repoService) {
        this.repoService = repoService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建代码仓库")
    public Result<RepoVO> create(@RequestBody CreateRepoRequest req) {
        return Result.ok(repoService.create(req, currentOrgId(), currentUserId()));
    }

    @PostMapping("/test-connection")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "测试代码仓库连接")
    public Result<RepoConnectionTestResult> testConnection(@RequestBody TestRepoConnectionRequest req) {
        return Result.ok(repoService.testConnection(req));
    }

    @GetMapping
    public Result<List<RepoVO>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(repoService.list(currentOrgId(), page, size));
    }

    @GetMapping("/{id}")
    public Result<RepoVO> get(@PathVariable("id") Long id) {
        return Result.ok(repoService.get(id));
    }

    @PutMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新代码仓库")
    public Result<RepoVO> update(@PathVariable("id") Long id, @RequestBody UpdateRepoRequest req) {
        return Result.ok(repoService.update(id, req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除代码仓库")
    public Result<Void> delete(@PathVariable("id") Long id) {
        repoService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/scan")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "扫描代码仓库")
    public Result<Void> startScan(@PathVariable("id") Long id) {
        repoService.startScan(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/{id}/conclusion")
    public Result<RepoConclusionVO> getConclusion(@PathVariable("id") Long id) {
        return Result.ok(repoService.getConclusion(id));
    }

    @PutMapping("/{id}/conclusion")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新代码仓库结论")
    public Result<RepoConclusionVO> updateConclusion(@PathVariable("id") Long id,
                                                      @RequestBody UpdateConclusionRequest req) {
        return Result.ok(repoService.updateConclusion(id, req, currentOrgId(), currentUserId()));
    }

    @GetMapping("/relations")
    public Result<List<RepoRelationVO>> listRelations(
            @RequestParam(value = "repoId", required = false) Long repoId) {
        if (repoId != null) {
            return Result.ok(repoService.listRelationsByRepoId(currentOrgId(), repoId));
        }
        return Result.ok(repoService.listRelations(currentOrgId()));
    }

    @PostMapping("/relations")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建代码仓库关联")
    public Result<RepoRelationVO> createRelation(@RequestBody CreateRelationRequest req) {
        return Result.ok(repoService.createRelation(req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/relations/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除代码仓库关联")
    public Result<Void> deleteRelation(@PathVariable("id") Long id) {
        repoService.deleteRelation(id, currentOrgId());
        return Result.ok(null);
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
