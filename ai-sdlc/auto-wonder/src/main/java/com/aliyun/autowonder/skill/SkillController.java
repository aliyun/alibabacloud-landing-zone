package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillConnectionTestVO;
import com.aliyun.autowonder.skill.dto.SkillPackageInspectVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看技能")
public class SkillController {

    private final SkillService skillService;
    private final SkillPackageService skillPackageService;
    private final SkillConnectionTestService skillConnectionTestService;

    public SkillController(SkillService skillService, SkillPackageService skillPackageService,
                           SkillConnectionTestService skillConnectionTestService) {
        this.skillService = skillService;
        this.skillPackageService = skillPackageService;
        this.skillConnectionTestService = skillConnectionTestService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建技能")
    public Result<SkillVO> create(@RequestBody CreateSkillRequest req) {
        return Result.ok(skillService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/package/inspect")
    public Result<SkillPackageInspectVO> inspectPackage(@RequestParam("file") MultipartFile file) {
        return Result.ok(skillPackageService.inspect(file));
    }

    @PostMapping("/package")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "从技能包创建技能")
    public Result<SkillVO> createFromPackage(@RequestParam("file") MultipartFile file,
			@RequestParam(value = "type", defaultValue = "SKILL") String type,
			@RequestParam(value = "name", required = false) String name,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam(value = "providers", required = false) List<String> providers) {
        return Result.ok(skillPackageService.createFromPackage(file, type, name, description, providers, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<SkillVO> get(@PathVariable("id") Long id) {
        return Result.ok(skillService.get(id));
    }

    @PostMapping("/{id}/connection-test")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "测试技能连接")
    public Result<SkillConnectionTestVO> testConnection(@PathVariable("id") Long id,
            @RequestParam(value = "executorId", required = false) Long executorId) {
        return Result.ok(skillConnectionTestService.test(id, currentWorkspaceId(), executorId));
    }

    @GetMapping
    public Result<List<SkillVO>> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(skillService.list(type, page, size));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新技能")
    public Result<SkillVO> update(@PathVariable("id") Long id, @RequestBody UpdateSkillRequest req) {
        return Result.ok(skillService.update(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PutMapping("/{id}/package")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新技能包")
    public Result<SkillVO> updatePackage(@PathVariable("id") Long id, @RequestParam("file") MultipartFile file,
			@RequestParam(value = "name", required = false) String name,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam(value = "providers", required = false) List<String> providers) {
        return Result.ok(skillPackageService.updatePackage(id, file, name, description, providers, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除技能")
    public Result<Void> delete(@PathVariable("id") Long id) {
        skillService.delete(id, currentWorkspaceId(), currentUserId());
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
