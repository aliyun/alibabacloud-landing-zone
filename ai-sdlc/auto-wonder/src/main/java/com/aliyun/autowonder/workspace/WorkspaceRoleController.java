package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.WorkspaceRoleRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceRoleVO;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/current/roles")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN,
        action = "管理工作空间角色", actionKey = "access.action.manage_workspace_roles")
public class WorkspaceRoleController {
    private final WorkspaceRoleService roleService;

    public WorkspaceRoleController(WorkspaceRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
            action = "查看工作空间角色", actionKey = "access.action.view_workspace_roles")
    public Result<List<WorkspaceRoleVO>> list() {
        return Result.ok(roleService.list(AutoWonderContext.get().getCurrentWorkspaceId()));
    }

    @PostMapping
    public Result<WorkspaceRoleVO> create(@RequestBody WorkspaceRoleRequest request) {
        return Result.ok(roleService.create(AutoWonderContext.get().getCurrentWorkspaceId(), request,
                AutoWonderContext.get().getUserId()));
    }

    @PutMapping("/{id}")
    public Result<WorkspaceRoleVO> update(@PathVariable("id") long id, @RequestBody WorkspaceRoleRequest request) {
        return Result.ok(roleService.update(AutoWonderContext.get().getCurrentWorkspaceId(), id, request,
                AutoWonderContext.get().getUserId()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id, @RequestParam("version") int version) {
        roleService.delete(AutoWonderContext.get().getCurrentWorkspaceId(), id, version,
                AutoWonderContext.get().getUserId());
        return Result.ok(null);
    }
}
